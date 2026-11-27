package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.objectstore.ObjectStoreClient;
import cn.byteforce.seedj.file.FileErrorCode;
import cn.byteforce.seedj.file.FileException;
import cn.byteforce.seedj.file.FileKeys;
import cn.byteforce.seedj.file.FileMetadata;
import cn.byteforce.seedj.file.FileService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

/**
 * 用 coord 对象存储（{@code ObjectStoreClient}）实现 seedj 的文件契约。
 *
 * <p>coord 对象不可覆盖、没有 content-type 与时间戳，所以每个文件写两个对象：随机 objectId 的
 * **数据对象**，和用 {@code sha256(scope, key)} 定位的 **元数据对象**（{@link CoordFileRecord}）。
 * 元数据对象是可见性的线性化点：数据先写、元数据 create-only 后写；元数据已存在时只删除本次的
 * 随机数据对象，不碰已有文件。删除相反，先删元数据对象（原子地让文件不可见），再尽力清理数据
 * 对象。
 *
 * <p>元数据不放 coord KV：0.4.6 的 Java SDK 只有 Txn v2，而 v1 对象存储要求 {@code kv_t} 关闭，
 * 两者不能同时满足。放在同一对象存储里也让本实现只依赖 {@code coord.storage}。
 *
 * <p>已知限制：SDK 只接受整块 {@code byte[]}，所以本实现按 {@code maxObjectBytes} 整块缓冲；
 * 超过上限在写入前失败。数据写成功而元数据写失败（进程崩溃等）会留下不可见的孤儿数据对象，
 * v1 不做后台 GC。
 */
final class CoordFileService implements FileService {

    private static final Logger LOG = LoggerFactory.getLogger(CoordFileService.class);

    /** coord 默认的单对象上限（{@code [object_storage].max_object_size_bytes}）。 */
    static final long MAX_OBJECT_BYTES = 268_435_456L;

    private static final int MAX_BUCKET_BYTES = 255;

    private static final int MAX_CONTENT_TYPE_CHARACTERS = 255;

    private static final byte[] META_DOMAIN = "seedj-file-meta\u0000".getBytes(StandardCharsets.UTF_8);

    private final ObjectStoreClient objects;
    private final CoordEndpoint endpoint;
    private final String scope;
    private final String bucket;
    private final long maxObjectBytes;
    private final Clock clock;

    CoordFileService(
            ObjectStoreClient objects,
            CoordEndpoint endpoint,
            String scope,
            String bucket,
            long maxObjectBytes,
            Clock clock) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("coord file scope must not be blank");
        }
        this.scope = scope;
        this.bucket = requireValidBucket(bucket);
        if (maxObjectBytes < 1 || maxObjectBytes > MAX_OBJECT_BYTES) {
            throw new IllegalArgumentException(
                    "coord file max-object-bytes must be in [1, " + MAX_OBJECT_BYTES + "]");
        }
        this.maxObjectBytes = maxObjectBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public FileMetadata put(
            String key, String contentType, long contentLength, InputStream content) {
        FileKeys.requireValid(key);
        validateContentType(contentType);
        if (contentLength <= 0) {
            throw new FileException(
                    FileErrorCode.INVALID_REQUEST, "file content must not be empty");
        }
        if (contentLength > maxObjectBytes) {
            throw new FileException(
                    FileErrorCode.TOO_LARGE,
                    "file content is larger than " + maxObjectBytes + " bytes");
        }
        if (content == null) {
            throw new FileException(FileErrorCode.INVALID_REQUEST, "file content is required");
        }
        byte[] data = readExactly(content, contentLength);
        byte[] dataObjectId = newDataObjectId();
        long revision = putBlob(dataObjectId, data, "put " + key);
        long createdAt = clock.millis();
        CoordFileRecord record =
                new CoordFileRecord(
                        key,
                        HexFormat.of().formatHex(dataObjectId),
                        data.length,
                        contentType,
                        revision,
                        createdAt);
        try {
            objects.put(bucket, metadataObjectId(scope, key), CoordJson.write(record));
        } catch (CoordException e) {
            deleteBlobQuietly(dataObjectId);
            if (e.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                throw new FileException(
                        FileErrorCode.ALREADY_EXISTS, "file already exists: " + key);
            }
            throw CoordErrors.file(e, endpoint, "put " + key);
        }
        return metadata(record);
    }

    @Override
    public InputStream open(String key) {
        FileKeys.requireValid(key);
        CoordFileRecord record = metadata(key).orElseThrow(() -> notFound(key));
        byte[] dataObjectId = parseObjectId(record.objectId());
        try {
            ObjectStoreClient.GetResult result = objects.get(bucket, dataObjectId);
            return new ByteArrayInputStream(result.data());
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw new FileException(
                        FileErrorCode.INTERNAL_ERROR,
                        "file record points to a missing object: " + key,
                        e);
            }
            throw CoordErrors.file(e, endpoint, "get " + key);
        }
    }

    @Override
    public Optional<FileMetadata> stat(String key) {
        FileKeys.requireValid(key);
        return metadata(key).map(CoordFileService::metadata);
    }

    @Override
    public boolean delete(String key) {
        FileKeys.requireValid(key);
        Optional<CoordFileRecord> current = metadata(key);
        if (current.isEmpty()) {
            return false;
        }
        try {
            if (!objects.delete(bucket, metadataObjectId(scope, key)).deleted()) {
                return false;
            }
        } catch (CoordException e) {
            throw CoordErrors.file(e, endpoint, "delete " + key);
        }
        deleteBlobQuietly(parseObjectId(current.get().objectId()));
        return true;
    }

    /** 元数据对象 id：{@code sha256("meta" | scope | key)}，固定 32 字节，不受 key 长度影响。 */
    static byte[] metadataObjectId(String scope, String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(META_DOMAIN);
            digest.update(scope.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private Optional<CoordFileRecord> metadata(String key) {
        try {
            ObjectStoreClient.GetResult result = objects.get(bucket, metadataObjectId(scope, key));
            return Optional.of(parse(result.data()));
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw CoordErrors.file(e, endpoint, "stat " + key);
        }
    }

    private long putBlob(byte[] objectId, byte[] data, String action) {
        try {
            return objects.put(bucket, objectId, data).revision();
        } catch (CoordException e) {
            throw CoordErrors.file(e, endpoint, action);
        }
    }

    private static CoordFileRecord parse(byte[] value) {
        try {
            CoordFileRecord record = CoordJson.read(value, CoordFileRecord.class);
            if (record.key() == null
                    || record.objectId() == null
                    || record.objectId().isEmpty()
                    || record.size() < 0) {
                throw new FileException(
                        FileErrorCode.INTERNAL_ERROR, "coord file record is incomplete");
            }
            return record;
        } catch (JacksonException e) {
            throw new FileException(
                    FileErrorCode.INTERNAL_ERROR, "coord file record is corrupt", e);
        }
    }

    private static FileMetadata metadata(CoordFileRecord record) {
        return new FileMetadata(
                record.key(),
                record.size(),
                record.contentType(),
                Long.toString(record.revision()),
                Instant.ofEpochMilli(record.createdAtEpochMilli()));
    }

    private static FileException notFound(String key) {
        return new FileException(FileErrorCode.NOT_FOUND, "file not found: " + key);
    }

    private static byte[] readExactly(InputStream content, long contentLength) {
        int expected = Math.toIntExact(contentLength);
        try {
            byte[] data = content.readNBytes(expected);
            if (data.length != expected) {
                throw new FileException(
                        FileErrorCode.INVALID_REQUEST,
                        "file content ended after " + data.length + " of " + expected + " bytes");
            }
            if (content.read() != -1) {
                throw new FileException(
                        FileErrorCode.INVALID_REQUEST,
                        "file content is longer than declared " + expected + " bytes");
            }
            return data;
        } catch (IOException e) {
            throw new FileException(FileErrorCode.INTERNAL_ERROR, "cannot read file content", e);
        }
    }

    private static byte[] newDataObjectId() {
        UUID uuid = UUID.randomUUID();
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private static byte[] parseObjectId(String objectId) {
        try {
            return HexFormat.of().parseHex(objectId);
        } catch (IllegalArgumentException e) {
            throw new FileException(
                    FileErrorCode.INTERNAL_ERROR, "coord file record has invalid objectId", e);
        }
    }

    private void deleteBlobQuietly(byte[] objectId) {
        try {
            objects.delete(bucket, objectId);
        } catch (CoordException e) {
            LOG.warn(
                    "coord file blob cleanup failed at {} for object {}: {}",
                    endpoint,
                    HexFormat.of().formatHex(objectId),
                    e.getErrorCode());
        }
    }

    private static String requireValidBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("coord file bucket must not be blank");
        }
        if (bucket.getBytes(StandardCharsets.UTF_8).length > MAX_BUCKET_BYTES) {
            throw new IllegalArgumentException(
                    "coord file bucket must not exceed " + MAX_BUCKET_BYTES + " UTF-8 bytes");
        }
        if (bucket.indexOf('/') >= 0) {
            throw new IllegalArgumentException("coord file bucket must not contain '/'");
        }
        if (bucket.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("coord file bucket must not contain NUL");
        }
        return bucket;
    }

    private static void validateContentType(String contentType) {
        if (contentType == null) {
            return;
        }
        if (contentType.isBlank() || contentType.length() > MAX_CONTENT_TYPE_CHARACTERS) {
            throw new FileException(
                    FileErrorCode.INVALID_REQUEST,
                    "file content-type must be non-blank and at most "
                            + MAX_CONTENT_TYPE_CHARACTERS
                            + " characters");
        }
    }
}
