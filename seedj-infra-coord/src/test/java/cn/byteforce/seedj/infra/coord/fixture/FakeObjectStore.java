package cn.byteforce.seedj.infra.coord.fixture;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.objectstore.ObjectStoreClient;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版 coord 对象存储：对齐 SDK 的三条语义——create-only、空数据被拒、缺失对象 stat 为空 /
 * get 抛 {@code NOT_FOUND}。测试夹具只保留 {@code FileService} 用到的行为。
 */
public final class FakeObjectStore implements ObjectStoreClient {

    private final Map<Key, byte[]> objects = new HashMap<>();
    private final Map<Key, Long> revisions = new HashMap<>();
    private long revision;
    private long putCount;
    private long putCalls;
    private long deleteCalls;
    private ErrorCode nextPutError;
    private ErrorCode nextGetError;
    private ErrorCode nextDeleteError;
    private long failPutOnCall;
    private ErrorCode failPutOnCallError;
    private long failDeleteOnCall;
    private ErrorCode failDeleteOnCallError;

    @Override
    public synchronized PutResult put(String bucket, byte[] objectId, byte[] data) {
        return put(bucket, objectId, data, 4 * 1024 * 1024);
    }

    @Override
    public synchronized PutResult put(
            String bucket, byte[] objectId, byte[] data, int chunkSizeBytes) {
        ErrorCode putError = nextPutError;
        nextPutError = null;
        failIf(putError, "put");
        putCalls++;
        if (putCalls == failPutOnCall) {
            failPutOnCall = 0;
            throw new CoordException(failPutOnCallError, "fake object store put failed");
        }
        if (bucket == null || bucket.isEmpty()) {
            throw new CoordException(ErrorCode.INTERNAL, "bucket must not be empty");
        }
        if (objectId == null || objectId.length == 0) {
            throw new CoordException(ErrorCode.INTERNAL, "objectId must not be empty");
        }
        if (data == null || data.length == 0) {
            throw new CoordException(ErrorCode.INTERNAL, "object data must not be empty");
        }
        if (chunkSizeBytes <= 0) {
            throw new CoordException(ErrorCode.INTERNAL, "chunk size must be positive");
        }
        Key key = new Key(bucket, objectId);
        if (objects.containsKey(key)) {
            throw new CoordException(ErrorCode.ALREADY_EXISTS, "object already exists");
        }
        long nextRevision = ++revision;
        objects.put(key, data.clone());
        revisions.put(key, nextRevision);
        putCount++;
        long chunks = (data.length + chunkSizeBytes - 1L) / chunkSizeBytes;
        return new PutResult(nextRevision, data.length, chunks);
    }

    @Override
    public synchronized GetResult get(String bucket, byte[] objectId) {
        ErrorCode getError = nextGetError;
        nextGetError = null;
        failIf(getError, "get");
        Key key = new Key(bucket, objectId);
        byte[] data = objects.get(key);
        if (data == null) {
            throw new CoordException(ErrorCode.NOT_FOUND, "object not found");
        }
        return new GetResult(info(key, data.length), data.clone());
    }

    @Override
    public synchronized Optional<ObjectInfo> stat(String bucket, byte[] objectId) {
        Key key = new Key(bucket, objectId);
        byte[] data = objects.get(key);
        return data == null ? Optional.empty() : Optional.of(info(key, data.length));
    }

    @Override
    public synchronized DeleteResult delete(String bucket, byte[] objectId) {
        ErrorCode deleteError = nextDeleteError;
        nextDeleteError = null;
        failIf(deleteError, "delete");
        deleteCalls++;
        if (deleteCalls == failDeleteOnCall) {
            failDeleteOnCall = 0;
            throw new CoordException(failDeleteOnCallError, "fake object store delete failed");
        }
        Key key = new Key(bucket, objectId);
        boolean removed = objects.remove(key) != null;
        revisions.remove(key);
        return new DeleteResult(removed, ++revision);
    }

    /** 直接移除对象，用于构造「sidecar 存在但 blob 丢失」的损坏状态。 */
    public synchronized void remove(String bucket, byte[] objectId) {
        Key key = new Key(bucket, objectId);
        objects.remove(key);
        revisions.remove(key);
    }

    public synchronized boolean contains(String bucket, byte[] objectId) {
        return objects.containsKey(new Key(bucket, objectId));
    }

    public synchronized long putCount() {
        return putCount;
    }

    public synchronized int objectCount() {
        return objects.size();
    }

    public synchronized void failNextPut(ErrorCode code) {
        nextPutError = code;
    }

    public synchronized void failNextGet(ErrorCode code) {
        nextGetError = code;
    }

    public synchronized void failNextDelete(ErrorCode code) {
        nextDeleteError = code;
    }

    /** 第 {@code call} 次 put（从 1 开始）失败；用于测「数据已写、元数据写失败」的回滚。 */
    public synchronized void failPutOnCall(long call, ErrorCode code) {
        failPutOnCall = call;
        failPutOnCallError = code;
    }

    /** 第 {@code call} 次 delete（从 1 开始）失败；用于测删除时数据清理失败。 */
    public synchronized void failDeleteOnCall(long call, ErrorCode code) {
        failDeleteOnCall = call;
        failDeleteOnCallError = code;
    }

    public synchronized void clear() {
        objects.clear();
        revisions.clear();
    }

    private ObjectInfo info(Key key, long size) {
        return new ObjectInfo(
                key.bucket(),
                key.objectId(),
                size,
                1,
                revisions.getOrDefault(key, 0L),
                true,
                true);
    }

    private static void failIf(ErrorCode code, String action) {
        if (code != null) {
            throw new CoordException(code, "fake object store " + action + " failed");
        }
    }

    private record Key(String bucket, byte[] objectId) {

        Key {
            objectId = objectId == null ? new byte[0] : objectId.clone();
        }

        @Override
        public byte[] objectId() {
            return objectId.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && bucket.equals(key.bucket)
                    && Arrays.equals(objectId, key.objectId);
        }

        @Override
        public int hashCode() {
            return 31 * bucket.hashCode() + Arrays.hashCode(objectId);
        }

        @Override
        public String toString() {
            return bucket + "/" + HexFormat.of().formatHex(objectId);
        }
    }
}
