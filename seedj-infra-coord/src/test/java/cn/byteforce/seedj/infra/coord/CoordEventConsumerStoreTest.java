package cn.byteforce.seedj.infra.coord;

import static cn.byteforce.seedj.infra.coord.CoordTestFixture.ENDPOINT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.spi.DeadLetter;
import cn.byteforce.seedj.eventbus.spi.DeliveryFailure;
import cn.byteforce.seedj.eventbus.spi.PollRequest;
import cn.byteforce.seedj.eventbus.spi.ReceivedEvent;
import cn.byteforce.seedj.infra.coord.fixture.FakeCoordBackend;
import cn.byteforce.seedj.infra.coord.fixture.FakeMqV2Client;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 契约之外的失败路径：retention、topic 集合变更、关闭与 agent 不可达。 */
class CoordEventConsumerStoreTest {

    private final FakeCoordBackend backend = new FakeCoordBackend();
    private final FakeMqV2Client mq = new FakeMqV2Client();
    private final MutableClock clock = new MutableClock(1_000_000L);

    private CoordEventConsumerStore store() {
        return new CoordEventConsumerStore(
                new CoordKvStore(backend.kv(), backend.txn()),
                mq,
                ENDPOINT,
                CoordKeySpace.of("seedj:test", "event-consumer"),
                "member-test",
                0,
                1_000,
                1,
                clock);
    }

    @Test
    @DisplayName("消息被 retention 清掉后转入死信并保留原因")
    void retentionMissBecomesDeadLetter() {
        CoordEventConsumerStore store = store();
        mq.send("test.topic", 0, "k", "payload".getBytes(StandardCharsets.UTF_8), headers("evt-1"));

        List<ReceivedEvent> first = store.poll(request("g1", Duration.ofMillis(100)));
        assertThat(first).hasSize(1);
        assertThat(first.get(0).attempts()).isEqualTo(1);

        clock.advance(Duration.ofMillis(2_000));
        mq.retainFrom("test.topic", 0, 1L);

        assertThat(store.poll(request("g1"))).isEmpty();

        List<DeadLetter> letters = store.deadLetters("test.topic", "g1", 10);
        assertThat(letters).hasSize(1);
        assertThat(letters.get(0).failure().errorType()).isEqualTo("seedj.retention.expired");
        assertThat(letters.get(0).message().eventId()).contains("retention-expired");
    }

    @Test
    @DisplayName("同一 group 换 topic 集合显式失败")
    void rejectsTopicSetChange() {
        CoordEventConsumerStore store = store();
        store.poll(request("g1"));

        assertThatThrownBy(
                        () ->
                                store.poll(
                                        new PollRequest(
                                                "g1",
                                                Set.of("other.topic"),
                                                1,
                                                Duration.ofSeconds(1))))
                .isInstanceOfSatisfying(
                        EventBusException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(EventBusErrorCode.INVALID_SUBSCRIBER));
    }

    @Test
    @DisplayName("close 之后 poll 失败、重复 close 幂等")
    void closeIsFinal() {
        CoordEventConsumerStore store = store();
        store.close();
        store.close();

        assertThatThrownBy(() -> store.poll(request("g1")))
                .isInstanceOfSatisfying(
                        EventBusException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(EventBusErrorCode.PROVIDER_CLOSED));
    }

    @Test
    @DisplayName("agent 不可达映射为 TRANSPORT_UNAVAILABLE 并点名地址")
    void unreachableAgentIsNamed() {
        mq.down(true);
        CoordEventConsumerStore store = store();

        assertThatThrownBy(() -> store.poll(request("g1")))
                .isInstanceOfSatisfying(
                        EventBusException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(EventBusErrorCode.TRANSPORT_UNAVAILABLE);
                            assertThat(e.getMessage()).contains(ENDPOINT.toString());
                        });
    }

    @Test
    @DisplayName("ack 之后账本清空，租约到期不会重投")
    void ackSettlesLedger() {
        CoordEventConsumerStore store = store();
        mq.send("test.topic", 0, "k", "p".getBytes(StandardCharsets.UTF_8), headers("evt-1"));

        ReceivedEvent received = pollOne(store);
        store.ack(received);
        clock.advance(Duration.ofSeconds(60));

        assertThat(store.poll(request("g1"))).isEmpty();
    }

    @Test
    @DisplayName("retry 延迟到期前不可见，到期后 attempts 递增")
    void retryRespectsDelay() {
        CoordEventConsumerStore store = store();
        mq.send("test.topic", 0, "k", "p".getBytes(StandardCharsets.UTF_8), headers("evt-1"));

        ReceivedEvent received = pollOne(store);
        store.retry(received, Duration.ofMillis(500), new DeliveryFailure("boom", "x", true));

        clock.advance(Duration.ofMillis(499));
        assertThat(store.poll(request("g1"))).isEmpty();

        clock.advance(Duration.ofMillis(2));
        ReceivedEvent again = pollOne(store);
        assertThat(again.attempts()).isEqualTo(2);
    }

    private ReceivedEvent pollOne(CoordEventConsumerStore store) {
        List<ReceivedEvent> events = store.poll(request("g1"));
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private static PollRequest request(String group) {
        return request(group, Duration.ofSeconds(30));
    }

    private static PollRequest request(String group, Duration lease) {
        return new PollRequest(group, Set.of("test.topic"), 10, lease);
    }

    private static Map<String, String> headers(String eventId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("eventId", eventId);
        headers.put("eventType", "test.event");
        headers.put("eventVersion", "1");
        headers.put("occurredAt", Instant.parse("2026-01-01T00:00:00Z").toString());
        return headers;
    }

    private static final class MutableClock extends Clock {

        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
