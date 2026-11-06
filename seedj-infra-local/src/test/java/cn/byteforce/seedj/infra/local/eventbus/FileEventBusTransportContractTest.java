package cn.byteforce.seedj.infra.local.eventbus;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.EventBusTransportContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileEventBusTransportContractTest extends EventBusTransportContractTest {

    @TempDir
    Path tempDir;

    @Override
    protected FileEventBusTransport newTransport() {
        return new FileEventBusTransport(tempDir);
    }

    @Test
    @DisplayName("payload 与 meta 落盘：meta 存在即表示写入完成，headers 可读")
    void writesPayloadAndMeta(@TempDir Path dir) throws Exception {
        FileEventBusTransport transport = new FileEventBusTransport(dir);
        EventMessage message = new EventMessage("evt-1", "order.cancelled", "agg-1",
                "order.cancelled", 3, "payload".getBytes(StandardCharsets.UTF_8),
                Map.of("traceId", "t-1"), Instant.parse("2026-01-01T00:00:00Z"));

        transport.send(message).toCompletableFuture().join();

        Path topicDir = dir.resolve("order.cancelled");
        try (Stream<Path> files = Files.list(topicDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .anyMatch(name -> name.endsWith(".meta"))
                    .anyMatch(name -> name.endsWith(".payload"));
        }

        Properties meta = new Properties();
        try (var in = Files.newInputStream(topicDir.resolve(
                "1767225600000-evt-1.meta"))) {
            meta.load(in);
        }
        assertThat(meta.getProperty("eventId")).isEqualTo("evt-1");
        assertThat(meta.getProperty("eventType")).isEqualTo("order.cancelled");
        assertThat(meta.getProperty("header.traceId")).isEqualTo("t-1");
        assertThat(meta.getProperty("payloadSha256")).isNotBlank();
    }

    @Test
    @DisplayName("同一 eventId 重复投递不产生第二个事件")
    void duplicateEventIdOverwrites(@TempDir Path dir) throws Exception {
        FileEventBusTransport transport = new FileEventBusTransport(dir);
        EventMessage message = EventMessage.of("e-1", "topic", "k", "p".getBytes(StandardCharsets.UTF_8));

        transport.send(message).toCompletableFuture().join();
        transport.send(message).toCompletableFuture().join();

        try (Stream<Path> files = Files.list(dir.resolve("topic"))) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".meta")).count())
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("topic 中的路径分隔符不会逃逸出基目录")
    void topicCannotEscapeBaseDir(@TempDir Path dir) throws Exception {
        FileEventBusTransport transport = new FileEventBusTransport(dir);
        transport.send(EventMessage.of("e-1", "../../etc", "k", new byte[0]))
                .toCompletableFuture().join();

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("_.._.._etc");
        }
    }
}
