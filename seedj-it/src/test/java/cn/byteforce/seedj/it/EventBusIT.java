package cn.byteforce.seedj.test;

import cn.byteforce.seedj.it.extension.ClusterExtension;
import cn.byteforce.seedj.it.extension.ClusterService;
import cn.byteforce.seedj.it.extension.EnableClusterServices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EventBus 端到端：事务性 outbox → relay → 磁盘队列，全部走真实的 HTTP / PostgreSQL / 文件系统。
 */
@ExtendWith(ClusterExtension.class)
@EnableClusterServices({"im"})
class EventBusIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("发布事件：落 outbox → relay 投递 → 状态 SENT 且磁盘出现消息文件")
    void publishIsPersistedAndDelivered(@ClusterService("im") RestTestClient im) throws Exception {
        Map<String, Object> published = postEvent(im,
                "it.delivered", "agg-1", "it-evt-1", "hello-eventbus");

        assertThat(published.get("eventId")).isEqualTo("it-evt-1");
        assertThat(published.get("outboxPresent")).isEqualTo(true);

        Map<String, Object> status = awaitStatus(im, "it-evt-1", "SENT");
        assertThat(status.get("count")).isEqualTo(1);
        assertThat(((Number) status.get("attempts")).intValue()).isGreaterThanOrEqualTo(1);

        String dir = (String) get(im, "/api/public/test/events/transport-dir").get("dir");
        assertThat(dir).isNotBlank();

        Path topicDir = Path.of(dir, "it.delivered");
        assertThat(topicDir).isDirectoryContaining(path ->
                path.getFileName().toString().contains("it-evt-1")
                        && path.getFileName().toString().endsWith(".meta"));

        try (Stream<Path> payloads = Files.list(topicDir)) {
            Path payload = payloads
                    .filter(p -> p.getFileName().toString().contains("it-evt-1"))
                    .filter(p -> p.getFileName().toString().endsWith(".payload"))
                    .findFirst().orElseThrow();
            assertThat(Files.readString(payload, StandardCharsets.UTF_8)).isEqualTo("hello-eventbus");
        }
    }

    @Test
    @DisplayName("同一 eventId 重复发布只落一行（outbox 幂等）")
    void publishIsIdempotent(@ClusterService("im") RestTestClient im) throws Exception {
        postEvent(im, "order.cancelled", "agg-2", "it-evt-dup", "payload");
        awaitStatus(im, "it-evt-dup", "SENT");

        postEvent(im, "order.cancelled", "agg-2", "it-evt-dup", "payload");

        Map<String, Object> status = get(im, "/api/public/test/events/it-evt-dup");
        assertThat(status.get("count")).isEqualTo(1);
    }

    @Test
    @DisplayName("同一分区内按写入顺序投递（第二个事件必须等第一个完成）")
    void partitionIsOrdered(@ClusterService("im") RestTestClient im) throws Exception {
        postEvent(im, "ordered", "agg-3", "it-evt-3a", "first");
        postEvent(im, "ordered", "agg-3", "it-evt-3b", "second");

        awaitStatus(im, "it-evt-3a", "SENT");
        Map<String, Object> second = awaitStatus(im, "it-evt-3b", "SENT");

        assertThat(((Number) second.get("sequence")).longValue())
                .isGreaterThan(((Number) get(im, "/api/public/test/events/it-evt-3a")
                        .get("sequence")).longValue());
    }

    // ==================== helpers ====================

    private static Map<String, Object> postEvent(RestTestClient client, String topic,
                                                 String partitionKey, String eventId, String payload) {
        var result = client.post()
                .uri(uri -> uri.path("/api/public/test/events")
                        .queryParam("topic", topic)
                        .queryParam("partitionKey", partitionKey)
                        .queryParam("eventId", eventId)
                        .queryParam("payload", payload)
                        .build())
                .exchange()
                .returnResult(String.class);
        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        return json(result.getResponseBodyContent());
    }

    private static Map<String, Object> get(RestTestClient client, String path) {
        var result = client.get().uri(path).exchange().returnResult(String.class);
        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        return json(result.getResponseBodyContent());
    }

    private static Map<String, Object> awaitStatus(RestTestClient client, String eventId,
                                                   String expected) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        Map<String, Object> last = Map.of();
        while (System.nanoTime() < deadline) {
            last = get(client, "/api/public/test/events/" + eventId);
            if (expected.equals(last.get("status"))) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("event " + eventId + " not " + expected + ", last=" + last);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(byte[] body) {
        return JSON.readValue(new String(body, StandardCharsets.UTF_8), Map.class);
    }
}
