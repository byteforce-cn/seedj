package cn.byteforce.seedj.test;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.it.extension.ClusterExtension;
import cn.byteforce.seedj.it.extension.ClusterService;
import cn.byteforce.seedj.it.extension.EnableClusterServices;
import cn.byteforce.seedj.it.fixture.hr.HrEventSubscriber;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/** 跨服务消费：IM 发布 → 文件队列 → HR 按消费者组消费并 ack。 */
@ExtendWith(ClusterExtension.class)
@EnableClusterServices({"im", "hr"})
class EventConsumeIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void clear() {
        HrEventSubscriber.clear();
    }

    @Test
    @DisplayName("IM 发布的事件被 HR 消费者组消费，且只处理一次")
    void publishOnImIsConsumedByHr(
            @ClusterService("im") RestTestClient im, @ClusterService("hr") RestTestClient hr) {
        String orderId = "o-" + UUID.randomUUID();
        String eventId = "evt-" + UUID.randomUUID();

        URI uri =
                UriComponentsBuilder.fromPath("/api/public/test/events")
                        .queryParam("topic", "it.consume")
                        .queryParam("partitionKey", orderId)
                        .queryParam("eventId", eventId)
                        .queryParam("payload", "{\"orderId\":\"" + orderId + "\"}")
                        .build()
                        .encode()
                        .toUri();

        var publish = im.post().uri(uri).exchange().returnResult(String.class);
        assertThat(publish.getStatus()).isEqualTo(HttpStatus.OK);

        Map<String, Object> consumed = awaitConsumed(hr, orderId);
        @SuppressWarnings("unchecked")
        Map<String, Object> byOrder = (Map<String, Object>) consumed.get("consumed");
        assertThat(byOrder).containsEntry(orderId, 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> awaitConsumed(RestTestClient hr, String orderId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Map<String, Object> last = Map.of();
        while (System.nanoTime() < deadline) {
            var result =
                    hr.get()
                            .uri("/api/public/test/events/consumed")
                            .exchange()
                            .returnResult(String.class);
            assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
            last =
                    JSON.readValue(
                            new String(result.getResponseBodyContent(), StandardCharsets.UTF_8),
                            Map.class);
            Map<String, Object> consumed = (Map<String, Object>) last.get("consumed");
            if (consumed != null && consumed.containsKey(orderId)) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("event " + orderId + " was not consumed, last=" + last);
    }
}
