package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.seedj.file.FileErrorCode;
import cn.byteforce.seedj.file.FileException;
import cn.byteforce.seedj.file.FileMetadata;
import cn.byteforce.seedj.infra.coord.fixture.FakeCoordBackend;
import cn.byteforce.seedj.infra.coord.fixture.FakeObjectStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** coord 实现里只有本适配器知道的行为：校验、回滚、损坏状态与错误码映射。 */
class CoordFileServiceTest {

    private static final String BUCKET = "seedj-files";

    private final FakeCoordBackend backend = new FakeCoordBackend();
    private final FakeObjectStore objects = new FakeObjectStore();
    private final CoordKeySpace keys = CoordKeySpace.of("seedj:test", "file");
    private final CoordEndpoint endpoint = new CoordEndpoint("127.0.0.1:19527");

    @Test
    @DisplayName("同 key 冲突只回滚本次 blob，已有文件不变")
    void sidecarConflictRollsBackOwnBlob() {
        CoordFileService service = service(1024);
        service.put("conflict.bin", "text/plain", 5, stream("first"));

        assertThatThrownBy(() -> service.put("conflict.bin", "text/plain", 6, stream("second")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.ALREADY_EXISTS));

        assertThat(objects.objectCount()).isEqualTo(1);
        assertThat(objects.putCount()).isEqualTo(2);
        assertThat(readAll(service.open("conflict.bin"))).isEqualTo(bytes("first"));
    }

    @Test
    @DisplayName("超过上限在写入前失败")
    void oversizeIsRejectedBeforeWriting() {
        CoordFileService service = service(4);

        assertThatThrownBy(() -> service.put("big.bin", "text/plain", 5, stream("12345")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.TOO_LARGE));

        assertThat(objects.putCount()).isZero();
        assertThat(service.stat("big.bin")).isEmpty();
    }

    @Test
    @DisplayName("短流与长流都不落盘")
    void lengthMismatchLeavesNoBlob() {
        CoordFileService service = service(1024);

        assertThatThrownBy(() -> service.put("short.bin", "text/plain", 5, stream("abc")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(FileErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.put("long.bin", "text/plain", 3, stream("abcd")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(FileErrorCode.INVALID_REQUEST));

        assertThat(objects.putCount()).isZero();
    }

    @Test
    @DisplayName("空内容与非法 content-type 被拒绝")
    void rejectsEmptyContentAndInvalidContentType() {
        CoordFileService service = service(1024);

        assertThatThrownBy(() -> service.put("empty.bin", "text/plain", 0, stream("")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(FileErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.put("blank-type.bin", " ", 1, stream("a")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(FileErrorCode.INVALID_REQUEST));
        assertThatThrownBy(
                        () ->
                                service.put(
                                        "long-type.bin",
                                        "a".repeat(256),
                                        1,
                                        stream("a")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(FileErrorCode.INVALID_REQUEST));

        assertThat(objects.putCount()).isZero();
    }

    @Test
    @DisplayName("sidecar 写失败时回滚 blob")
    void sidecarWriteFailureRollsBackBlob() {
        CoordFileService service = service(1024);
        backend.down(true);

        assertThatThrownBy(() -> service.put("down.bin", "text/plain", 3, stream("abc")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(FileErrorCode.PROVIDER_UNAVAILABLE);
                            assertThat(e.isRetryable()).isTrue();
                        });

        assertThat(objects.objectCount()).isZero();
    }

    @Test
    @DisplayName("sidecar 损坏是内部错误，不是 NOT_FOUND")
    void corruptRecordIsInternalError() {
        CoordFileService service = service(1024);
        backend.seed(keys.key("bad.bin"), "{oops");

        assertThatThrownBy(() -> service.stat("bad.bin"))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(FileErrorCode.INTERNAL_ERROR));
        assertThatThrownBy(() -> service.open("bad.bin"))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(FileErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("sidecar 指向缺失 blob 是内部错误")
    void missingBlobIsInternalError() {
        CoordFileService service = service(1024);
        String objectId = HexFormat.of().formatHex(new byte[] {1, 2, 3});
        backend.seed(
                keys.key("gone.bin"),
                json(new CoordFileRecord("gone.bin", objectId, 3, "text/plain", 1, 0)));

        assertThatThrownBy(() -> service.open("gone.bin"))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(FileErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("删除时 blob 清理失败不影响结果，映射已摘除")
    void deleteToleratesBlobDeleteFailure() {
        CoordFileService service = service(1024);
        service.put("orphan.bin", "text/plain", 3, stream("abc"));
        objects.failNextDelete(ErrorCode.INTERNAL);

        assertThat(service.delete("orphan.bin")).isTrue();

        assertThat(service.stat("orphan.bin")).isEmpty();
        assertThat(objects.objectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("coord 错误码映射到文件错误码")
    void mapsCoordErrorCodes() {
        CoordFileService service = service(1024);
        objects.failNextPut(ErrorCode.QUOTA_EXCEEDED);
        assertThatThrownBy(() -> service.put("quota.bin", "text/plain", 1, stream("a")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.TOO_LARGE));

        service.put("read.bin", "text/plain", 1, stream("a"));
        objects.failNextGet(ErrorCode.UNAVAILABLE);
        assertThatThrownBy(() -> service.open("read.bin"))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(FileErrorCode.PROVIDER_UNAVAILABLE);
                            assertThat(e.isRetryable()).isTrue();
                        });

        objects.failNextGet(ErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> service.open("read.bin"))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(FileErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("构造期校验 bucket 与单文件上限")
    void validatesConfiguration() {
        assertThatThrownBy(() -> service(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(CoordFileService.MAX_OBJECT_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serviceWithBucket("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serviceWithBucket(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("元数据回读 key、size、content-type 与时间")
    void statReturnsMetadata() {
        CoordFileService service = service(1024);

        service.put("meta.txt", "text/plain", 5, stream("hello"));

        FileMetadata metadata = service.stat("meta.txt").orElseThrow();
        assertThat(metadata.key()).isEqualTo("meta.txt");
        assertThat(metadata.size()).isEqualTo(5);
        assertThat(metadata.contentType()).isEqualTo("text/plain");
        assertThat(metadata.version()).isNotBlank();
        assertThat(metadata.createdAt()).isNotNull();
    }

    private CoordFileService service(long maxObjectBytes) {
        return serviceWithBucketAndLimit(BUCKET, maxObjectBytes);
    }

    private CoordFileService serviceWithBucket(String bucket) {
        return serviceWithBucketAndLimit(bucket, 1024);
    }

    private CoordFileService serviceWithBucketAndLimit(String bucket, long maxObjectBytes) {
        return new CoordFileService(
                objects,
                new CoordKvStore(backend.kv(), backend.txn()),
                keys,
                endpoint,
                bucket,
                maxObjectBytes,
                Clock.systemUTC());
    }

    private static String json(CoordFileRecord record) {
        return new String(CoordJson.write(record), StandardCharsets.UTF_8);
    }

    private static InputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream input) {
        try (input) {
            return input.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
