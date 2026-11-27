package cn.byteforce.seedj.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FileService} 契约测试套件。
 *
 * <p>只断言语义，不碰物理实现：create-only、元数据、缺失语义、长度校验、非法 key、并发只成功一个。 实现方只需要提供 {@link
 * #newFileService()}，并保证每个用例拿到干净的存储。
 */
public abstract class FileServiceContractTest {

    protected abstract FileService newFileService();

    private FileService service;

    @BeforeEach
    void setUp() {
        service = newFileService();
    }

    @AfterEach
    void tearDown() {
        service = null;
    }

    @Test
    @DisplayName("二进制内容原样往返")
    void roundTripsBinaryContent() {
        byte[] payload = new byte[256];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) index;
        }

        service.put(
                "contract/binary.bin", "application/octet-stream", payload.length, stream(payload));

        assertThat(readAll(service.open("contract/binary.bin"))).isEqualTo(payload);
    }

    @Test
    @DisplayName("stat 回读 key、size 与 content-type")
    void reportsSizeContentTypeAndKey() {
        byte[] payload = "hello-file".getBytes(StandardCharsets.UTF_8);

        service.put("contract/meta.txt", "text/plain", payload.length, stream(payload));

        FileMetadata metadata = service.stat("contract/meta.txt").orElseThrow();
        assertThat(metadata.key()).isEqualTo("contract/meta.txt");
        assertThat(metadata.size()).isEqualTo(payload.length);
        assertThat(metadata.contentType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("同 key 第二次 put 失败且不破坏第一个文件")
    void secondPutOnSameKeyFailsWithoutTouchingFirst() {
        service.put("contract/one.bin", "application/octet-stream", 4, stream("AAAA"));

        assertThatThrownBy(() -> service.put("contract/one.bin", "text/plain", 3, stream("BBB")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.ALREADY_EXISTS));

        assertThat(readAll(service.open("contract/one.bin")))
                .isEqualTo("AAAA".getBytes(StandardCharsets.UTF_8));
        assertThat(service.stat("contract/one.bin").orElseThrow().size()).isEqualTo(4);
    }

    @Test
    @DisplayName("缺失 key 的 stat 返回空")
    void statOfMissingKeyIsEmpty() {
        assertThat(service.stat("contract/missing.bin")).isEmpty();
    }

    @Test
    @DisplayName("缺失 key 的 open 抛 NOT_FOUND")
    void openOfMissingKeyThrowsNotFound() {
        assertThatThrownBy(() -> service.open("contract/missing.bin"))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("delete 移除文件且幂等")
    void deleteRemovesFileAndIsIdempotent() {
        service.put("contract/delete.bin", "text/plain", 3, stream("abc"));

        assertThat(service.delete("contract/delete.bin")).isTrue();
        assertThat(service.delete("contract/delete.bin")).isFalse();
        assertThat(service.stat("contract/delete.bin")).isEmpty();
        assertThatThrownBy(() -> service.open("contract/delete.bin"))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("delete 之后同 key 可以重建")
    void recreateAfterDeleteSucceeds() {
        service.put("contract/recreate.bin", "text/plain", 3, stream("old"));
        service.delete("contract/recreate.bin");

        service.put("contract/recreate.bin", "text/plain", 3, stream("new"));

        assertThat(readAll(service.open("contract/recreate.bin")))
                .isEqualTo("new".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("空内容被拒绝且不落盘")
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> service.put("contract/empty.bin", "text/plain", 0, stream("")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.INVALID_REQUEST));

        assertThat(service.stat("contract/empty.bin")).isEmpty();
    }

    @Test
    @DisplayName("非法 key 显式失败")
    void rejectsInvalidKey() {
        for (String key : List.of(" ", "contract/../escape", "/absolute")) {
            assertThatThrownBy(() -> service.put(key, "text/plain", 1, stream("a")))
                    .isInstanceOfSatisfying(
                            FileException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.INVALID_KEY));
        }
    }

    @Test
    @DisplayName("流比声明短时拒绝且不落盘")
    void rejectsStreamShorterThanDeclaredLength() {
        assertThatThrownBy(() -> service.put("contract/short.bin", "text/plain", 5, stream("abc")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.INVALID_REQUEST));

        assertThat(service.stat("contract/short.bin")).isEmpty();
    }

    @Test
    @DisplayName("流比声明长时拒绝且不落盘")
    void rejectsStreamLongerThanDeclaredLength() {
        assertThatThrownBy(() -> service.put("contract/long.bin", "text/plain", 3, stream("abcd")))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.INVALID_REQUEST));

        assertThat(service.stat("contract/long.bin")).isEmpty();
    }

    @Test
    @DisplayName("并发 put 同一个 key 只有一个成功")
    void concurrentPutHasSingleWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (String payload : List.of("AAAA", "BBBB")) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await(10, TimeUnit.SECONDS);
                                    try {
                                        service.put(
                                                "contract/concurrent.bin",
                                                "text/plain",
                                                4,
                                                stream(payload));
                                        return true;
                                    } catch (FileException e) {
                                        assertThat(e.getErrorCode())
                                                .isEqualTo(FileErrorCode.ALREADY_EXISTS);
                                        return false;
                                    }
                                }));
            }

            start.countDown();

            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(service.stat("contract/concurrent.bin").orElseThrow().size()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    protected static InputStream stream(String value) {
        return stream(value.getBytes(StandardCharsets.UTF_8));
    }

    protected static InputStream stream(byte[] value) {
        return new ByteArrayInputStream(value);
    }

    protected static byte[] readAll(InputStream input) {
        try (input) {
            return input.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
