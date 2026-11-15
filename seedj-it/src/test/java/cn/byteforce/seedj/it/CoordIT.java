package cn.byteforce.seedj.test;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.mq.v2.MqV2Message;
import cn.byteforce.coord.sdk.mq.v2.MqV2Poll;
import cn.byteforce.coord.sdk.mq.v2.MqV2PollResult;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicDescription;
import cn.byteforce.seedj.it.extension.ClusterCoord;
import cn.byteforce.seedj.it.extension.ClusterExtension;
import cn.byteforce.seedj.it.extension.ClusterService;
import cn.byteforce.seedj.it.extension.EnableClusterServices;
import cn.byteforce.seedj.it.extension.EnableCoordCluster;
import cn.byteforce.seedj.it.fixture.hr.HrEventSubscriber;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

/**
 * coord 能力端到端：集群 IT 的服务全部切到 coord provider，底层是真的 coord v2 集群（server + agent 容器）。
 *
 * <p>覆盖 {@code seedj-infra-coord} 当前发布的六个能力：ID、编号、全局序列、分布式锁、EventBus 发布、EventBus 消费。
 * 断言分两层：服务侧 HTTP 观测（provider 类型、结果值）与 coord 侧状态（topic / 消息 / 顺序），避免只证明「服务自己说自己成功」。
 *
 * <p>PKI/Transit 仍由 {@code seedj-infra-local} 提供：coord 侧适配器尚未交付（设计 P2），不在本类范围内。
 */
@ExtendWith(ClusterExtension.class)
@EnableClusterServices({"im", "hr"})
@EnableCoordCluster
class CoordIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(20);

    @BeforeEach
    void clearConsumed() {
        HrEventSubscriber.clear();
    }

    @Test
    @DisplayName("基础设施能力都装配成 Coord* provider；ID 在 IM 上由 coord 提供")
    void providersComeFromCoord(
            @ClusterService("im") RestTestClient im, @ClusterService("hr") RestTestClient hr) {
        // IM：默认 IdGenerator 就是 coord idgen。
        assertThat(get(im, "/api/public/test/coord/providers"))
                .hasSize(6)
                .allSatisfy(
                        (capability, implementation) ->
                                assertThat(String.valueOf(implementation))
                                        .as("capability %s 应由 coord 实现", capability)
                                        .startsWith("Coord"));

        // HR：五条基础设施能力同样来自 coord；IdGenerator 被员工号生成器覆盖（业务实现）。
        Map<String, Object> hrProviders = get(hr, "/api/public/test/coord/providers");
        assertThat(hrProviders).hasSize(6);
        for (String capability :
                List.of(
                        "numbering",
                        "globalSequencer",
                        "lockProvider",
                        "eventBusTransport",
                        "eventConsumerStore")) {
            assertThat(String.valueOf(hrProviders.get(capability)))
                    .as("capability %s 应由 coord 实现", capability)
                    .startsWith("Coord");
        }
        assertThat(String.valueOf(hrProviders.get("idGenerator")))
                .as("HR 的员工号生成器是业务实现，不应被 coord 覆盖")
                .startsWith("HrEmployeeConfiguration");
    }

    @Test
    @DisplayName("ID 是十进制字符串且严格递增")
    void idsAreDecimalAndIncreasing(@ClusterService("im") RestTestClient im) {
        List<String> ids = ids(im, 10);

        assertThat(ids).allSatisfy(id -> assertThat(id).matches("\\d+")).doesNotHaveDuplicates();
        assertThat(increasing(ids)).as("ID 必须单调递增: %s", ids).isTrue();
    }

    @Test
    @DisplayName("编号跨实例共享同一序列：两个服务交替取号严格递增")
    void numberingIsSharedAcrossServices(
            @ClusterService("im") RestTestClient im, @ClusterService("hr") RestTestClient hr) {
        String scope = "it_scope";
        long first = number(im, scope);
        long second = number(im, scope);
        long third = number(hr, scope);
        long fourth = number(im, scope);

        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
        assertThat(fourth).isGreaterThan(third);
    }

    @Test
    @DisplayName("全局序列跨实例单调：HR 取号后 IM 取号更大")
    void globalSequencerIsSharedAcrossServices(
            @ClusterService("im") RestTestClient im, @ClusterService("hr") RestTestClient hr) {
        long fromHr = sequence(hr);
        long fromIm = sequence(im);

        assertThat(fromIm).isGreaterThan(fromHr);
    }

    @Test
    @DisplayName("锁跨服务互斥：IM 持锁时 HR 抢不到，释放后 HR 可获取")
    void lockIsMutuallyExclusiveAcrossServices(
            @ClusterService("im") RestTestClient im, @ClusterService("hr") RestTestClient hr) {
        String key = "it-lock-" + UUID.randomUUID();

        assertThat(acquire(im, key, 30_000)).isTrue();
        assertThat(acquire(hr, key, 30_000)).as("IM 持有期间 HR 不得拿到同一把锁").isFalse();

        release(im, key);
        assertThat(acquire(hr, key, 30_000)).as("释放后 HR 应能获取").isTrue();
        release(hr, key);
    }

    @Test
    @DisplayName("租约到期后另一个服务可接管")
    void expiredLeaseIsReacquirable(
            @ClusterService("im") RestTestClient im, @ClusterService("hr") RestTestClient hr)
            throws InterruptedException {
        String key = "it-lock-ttl-" + UUID.randomUUID();

        assertThat(acquire(im, key, 300)).isTrue();
        Thread.sleep(1_200);
        assertThat(acquire(hr, key, 30_000)).as("租约过期后 HR 应能接管").isTrue();

        release(hr, key);
    }

    @Test
    @DisplayName("事件经 outbox relay 投递到 coord topic：稳定分区 + eventId header")
    void eventIsPublishedToCoordTopic(
            @ClusterService("im") RestTestClient im, @ClusterCoord CoordClient coord) {
        String eventId = "evt-coord-" + UUID.randomUUID();
        String partitionKey = "agg-coord";

        publish(im, "it.coord", partitionKey, eventId, "hello-coord");
        awaitSent(im, eventId);

        MqV2TopicDescription topic = awaitTopic(coord, "it.coord");
        List<MqV2Message> messages = awaitMessages(coord, topic, eventId);
        assertThat(messages).hasSize(1);

        MqV2Message message = messages.get(0);
        assertThat(new String(message.payload(), StandardCharsets.UTF_8)).isEqualTo("hello-coord");
        assertThat(new String(message.key(), StandardCharsets.UTF_8)).isEqualTo(partitionKey);
        assertThat(message.headers()).containsEntry("eventId", eventId);
        // 三个服务共享同一个 PostgreSQL outbox，任何服务的 relay 都可以领取并投递；
        // 因此 producer 是「某个 seedj-it 实例」，不固定是 IM。
        assertThat(message.producer()).isIn("im-node-1", "hr-node-1", "iam-node-1");
        assertThat(message.partition())
                .as("partitionKey 相同时必须落到同一分区")
                .isEqualTo(Math.floorMod(partitionKey.hashCode(), topic.partitionCount()));
    }

    @Test
    @DisplayName("IM 发布的事件由 HR 的 coord 消费者组消费，且只处理一次")
    void eventIsConsumedOnceByCoordConsumer(
            @ClusterService("im") RestTestClient im, @ClusterService("hr") RestTestClient hr) {
        String orderId = "o-" + UUID.randomUUID();
        String eventId = "evt-consume-" + UUID.randomUUID();

        publish(im, "it.consume", orderId, eventId, "{\"orderId\":\"" + orderId + "\"}");

        assertThat(awaitConsumed(hr, orderId)).containsEntry(orderId, 1);
        sleep(1_000);
        assertThat(consumed(hr)).as("同一事件不得被重复处理").containsEntry(orderId, 1);
    }

    @Test
    @DisplayName("可重试失败按退避重投，超过上限后进入 coord 死信且不重复")
    void failedEventIsRetriedThenDeadLettered(
            @ClusterService("im") RestTestClient im, @ClusterService("hr") RestTestClient hr) {
        String orderId = "o-fail-" + UUID.randomUUID();
        String eventId = "evt-fail-" + UUID.randomUUID();

        publish(im, "it.failure", orderId, eventId, "{\"orderId\":\"" + orderId + "\"}");

        Map<String, Object> letter = awaitDeadLetter(hr, "it.failure", "hr-failing", eventId);
        assertThat(letter.get("topic")).isEqualTo("it.failure");
        assertThat(letter.get("group")).isEqualTo("hr-failing");
        assertThat(String.valueOf(letter.get("payload"))).contains(orderId);

        @SuppressWarnings("unchecked")
        Map<String, Object> failure = (Map<String, Object>) letter.get("failure");
        assertThat(failure.get("retryable")).isEqualTo(true);
        assertThat(failure.get("type")).isEqualTo("java.lang.IllegalStateException");

        sleep(1_000);
        long sameEvent =
                deadLetters(hr, "it.failure", "hr-failing").stream()
                        .filter(candidate -> eventId.equals(candidate.get("eventId")))
                        .count();
        assertThat(sameEvent).as("同一事件只能落一条死信").isEqualTo(1);
    }

    // ==================== 服务侧驱动 ====================

    private static List<String> ids(RestTestClient client, int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(String.valueOf(get(client, "/api/public/test/coord/id").get("id")));
        }
        return ids;
    }

    private static long number(RestTestClient client, String scope) {
        URI uri =
                UriComponentsBuilder.fromPath("/api/public/test/coord/number")
                        .queryParam("scope", scope)
                        .build()
                        .toUri();
        return ((Number) get(client, uri).get("value")).longValue();
    }

    private static long sequence(RestTestClient client) {
        return ((Number) get(client, "/api/public/test/coord/sequence").get("value")).longValue();
    }

    private static boolean acquire(RestTestClient client, String key, long ttlMillis) {
        URI uri =
                UriComponentsBuilder.fromPath("/api/public/test/coord/lock/acquire")
                        .queryParam("key", key)
                        .queryParam("ttlMillis", ttlMillis)
                        .build()
                        .toUri();
        return (Boolean) post(client, uri).get("acquired");
    }

    private static void release(RestTestClient client, String key) {
        URI uri =
                UriComponentsBuilder.fromPath("/api/public/test/coord/lock/release")
                        .queryParam("key", key)
                        .build()
                        .toUri();
        assertThat(post(client, uri).get("released")).isEqualTo(true);
    }

    private static void publish(
            RestTestClient client, String topic, String partitionKey, String eventId, String payload) {
        URI uri =
                UriComponentsBuilder.fromPath("/api/public/test/events")
                        .queryParam("topic", topic)
                        .queryParam("partitionKey", partitionKey)
                        .queryParam("eventId", eventId)
                        .queryParam("payload", payload)
                        .build()
                        .encode()
                        .toUri();
        assertThat(post(client, uri).get("eventId")).isEqualTo(eventId);
    }

    private static void awaitSent(RestTestClient client, String eventId) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        Map<String, Object> last = Map.of();
        while (System.nanoTime() < deadline) {
            last = get(client, "/api/public/test/events/" + eventId);
            if ("SENT".equals(last.get("status"))) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("event " + eventId + " was not sent, last=" + last);
    }

    // ==================== coord 侧断言 ====================

    private static MqV2TopicDescription awaitTopic(CoordClient coord, String topic) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                return coord.mqV2().describeTopic(topic);
            } catch (CoordException e) {
                if (e.getErrorCode() != ErrorCode.TOPIC_NOT_FOUND) {
                    throw e;
                }
            }
            sleep(100);
        }
        throw new AssertionError("coord topic " + topic + " was not created");
    }

    /** 扫描 topic 的每个分区（从 offset 0），等 eventId 出现。 */
    private static List<MqV2Message> awaitMessages(
            CoordClient coord, MqV2TopicDescription topic, String eventId) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        List<MqV2Message> last = List.of();
        while (System.nanoTime() < deadline) {
            last = scan(coord, topic, eventId);
            if (!last.isEmpty()) {
                return last;
            }
            sleep(100);
        }
        throw new AssertionError("event " + eventId + " not found on coord topic " + topic.topic());
    }

    private static List<MqV2Message> scan(
            CoordClient coord, MqV2TopicDescription topic, String eventId) {
        List<MqV2Message> matches = new ArrayList<>();
        for (int partition = 0; partition < topic.partitionCount(); partition++) {
            MqV2PollResult result =
                    coord.mqV2()
                            .poll(
                                    new MqV2Poll(
                                            topic.topic(),
                                            partition,
                                            topic.topicEpoch(),
                                            0L,
                                            100,
                                            1_048_576,
                                            0));
            result.messages().stream()
                    .filter(message -> eventId.equals(message.headers().get("eventId")))
                    .forEach(matches::add);
        }
        return matches;
    }

    private static Map<String, Integer> consumed(RestTestClient hr) {
        @SuppressWarnings("unchecked")
        Map<String, Integer> consumed =
                (Map<String, Integer>) get(hr, "/api/public/test/events/consumed").get("consumed");
        return consumed;
    }

    private static Map<String, Integer> awaitConsumed(RestTestClient hr, String orderId) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        Map<String, Integer> last = Map.of();
        while (System.nanoTime() < deadline) {
            last = consumed(hr);
            if (last.containsKey(orderId)) {
                return last;
            }
            sleep(100);
        }
        throw new AssertionError("event " + orderId + " was not consumed, last=" + last);
    }

    private static List<Map<String, Object>> deadLetters(
            RestTestClient hr, String topic, String group) {
        URI uri =
                UriComponentsBuilder.fromPath("/api/public/test/events/dead-letters")
                        .queryParam("topic", topic)
                        .queryParam("group", group)
                        .queryParam("limit", 20)
                        .build()
                        .toUri();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> letters =
                (List<Map<String, Object>>) get(hr, uri).get("deadLetters");
        return letters;
    }

    private static Map<String, Object> awaitDeadLetter(
            RestTestClient hr, String topic, String group, String eventId) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (System.nanoTime() < deadline) {
            for (Map<String, Object> letter : deadLetters(hr, topic, group)) {
                if (eventId.equals(letter.get("eventId"))) {
                    return letter;
                }
            }
            sleep(200);
        }
        throw new AssertionError("event " + eventId + " was not dead-lettered");
    }

    // ==================== HTTP ====================

    private static Map<String, Object> get(RestTestClient client, String path) {
        return get(client, URI.create(path));
    }

    private static Map<String, Object> get(RestTestClient client, URI uri) {
        var result = client.get().uri(uri).exchange().returnResult(String.class);
        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        return json(result.getResponseBodyContent());
    }

    private static Map<String, Object> post(RestTestClient client, URI uri) {
        var result = client.post().uri(uri).exchange().returnResult(String.class);
        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        return json(result.getResponseBodyContent());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(byte[] body) {
        return JSON.readValue(new String(body, StandardCharsets.UTF_8), Map.class);
    }

    private static boolean increasing(List<String> ids) {
        for (int i = 1; i < ids.size(); i++) {
            if (Long.parseLong(ids.get(i)) <= Long.parseLong(ids.get(i - 1))) {
                return false;
            }
        }
        return true;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
