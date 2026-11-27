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
 * <p>blob 不可覆盖、没有 content-type 与时间戳，所以元数据放在 coord KV 的 sidecar 里：{@code
 * <namespace>:coord:file:<key>} → {@link CoordFileRecord}。写入顺序是 blob 先、sidecar 的 {@code
 * putIfAbsent} 后：CAS 成功才是创建成功的线性化点；CAS 失败只删除本次上传的随机 objectId，不碰已有
 * 文件。删除相反，先摘 sidecar（原子地让对象不可见），再尽力清理 blob。
 *
 * <p>已知限制：SDK 只接受整块 {@code byte[]}，所以本实现按 {@code maxObjectBytes} 整块缓冲；
 * 超过上限在写入前失败。blob 写成功而 sidecar 写失败（进程崩溃等）会留下不可见的孤儿 blob，v1
 * 不做后台 GC。
 */
final class CoordFileService implements FileService {

    private static final Logger LOG = LoggerFactory.getLogger(CoordFileService.class);

    /** coord 默认的单对象上限（{@code [object_storage].max_object_size_bytes}）。 */
    static final long MAX_OBJECT_BYTES = 268_435_456L;

    private static final int MAX_BUCKET_BYTES = 255;

    private static final int MAX_CONTENT_TYPE_CHARACTERS = 255;

    private static final int DELETE_ATTEMPTS = 3;

    private final ObjectStoreClient objects;
    private final CoordKvStore store;
    private final CoordKeySpace keys;
    private final CoordEndpoint endpoint;
    private final String bucket;
    private final long maxObjectBytes;
    private final Clock clock;

    CoordFileService(
            ObjectStoreClient objects,
            CoordKvStore store,
            CoordKeySpace keys,
            CoordEndpoint endpoint,
            String bucket,
            long maxObjectBytes,
            Clock clock) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.store = Objects.requireNonNull(store, "store");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
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
        byte[] objectId = newObjectId();
        long revision = putBlob(key, objectId, data);
        long createdAt = clock.millis();
        CoordFileRecord record =
                new CoordFileRecord(
                        key,
                        HexFormat.of().formatHex(objectId),
                        data.length,
                        contentType,
                        revision,
                        createdAt);
        try {
            if (!store.putIfAbsent(keys.key(key), CoordJson.write(record))) {
                deleteBlobQuietly(objectId);
                throw new FileException(
                        FileErrorCode.ALREADY_EXISTS, "file already exists: " + key);
            }
        } catch (CoordException e) {
            deleteBlobQuietly(objectId);
            throw CoordErrors.file(e, endpoint, "put");
        }
        return metadata(record);
    }

    @Override
    public InputStream open(String key) {
        FileKeys.requireValid(key);
        CoordFileRecord record = record(key).orElseThrow(() -> notFound(key));
        byte[] objectId = parseObjectId(record.objectId());
        try {
            ObjectStoreClient.GetResult result = objects.get(bucket, objectId);
            return new ByteArrayInputStream(result.data());
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw new FileException(
                        FileErrorCode.INTERNAL_ERROR,
                        "file record points to a missing object: " + key,
                        e);
            }
            throw CoordErrors.file(e, endpoint, "get");
        }
    }

    @Override
    public Optional<FileMetadata> stat(String key) {
        FileKeys.requireValid(key);
        return record(key).map(CoordFileService::metadata);
    }

    @Override
    public boolean delete(String key) {
        FileKeys.requireValid(key);
        String recordKey = keys.key(key);
        try {
            for (int attempt = 0; attempt < DELETE_ATTEMPTS; attempt++) {
                Optional<CoordKvStore.Entry> current = store.readCurrent(recordKey);
                if (current.isEmpty()) {
                    return false;
                }
                CoordFileRecord record = record(current.get());
                if (!store.removeIfMatch(recordKey, current.get().value())) {
                    continue;
                }
                deleteBlobQuietly(parseObjectId(record.objectId()));
                return true;
            }
            return false;
        } catch (CoordException e) {
            throw CoordErrors.file(e, endpoint, "delete");
        }
    }

    private long putBlob(String key, byte[] objectId, byte[] data) {
        try {
            return objects.put(bucket, objectId, data).revision();
        } catch (CoordException e) {
            throw CoordErrors.file(e, endpoint, "put " + key);
        }
    }

    private Optional<CoordFileRecord> record(String key) {
        Optional<CoordKvStore.Entry> entry;
        try {
            entry = store.get(keys.key(key));
        } catch (CoordException e) {
            throw CoordErrors.file(e, endpoint, "stat " + key);
        }
        return entry.map(value -> record(value));
    }

    private CoordFileRecord record(CoordKvStore.Entry entry) {
        try {
            CoordFileRecord record = CoordJson.read(entry.value(), CoordFileRecord.class);
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

    private static byte[] newObjectId() {
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
