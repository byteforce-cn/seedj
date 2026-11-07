package cn.byteforce.seedj.eventbus.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.EventMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EventConsumerStore 契约测试套件：消费者组、ack、租约、重试、死信。
 *
 * <p>实现方需要提供 {@link #store()}、{@link #send(EventMessage)} 与 {@link #reset()}。
 */
public abstract class EventConsumerStoreContractTest {

    protected abstract EventConsumerStore store();

    /** 通过同一队列的发送侧投递一条消息。 */
    protected abstract void send(EventMessage message);

    /** 清空存储，保证用例隔离。 */
    protected abstract void reset();

    @BeforeEach
    void cleanState() {
        reset();
    }

    @AfterEach
    void closeStore() {
        store().close();
    }

    protected static EventMessage message(String eventId, String payload) {
        return new EventMessage(
                eventId,
                "test.topic",
                "agg-1",
                "test.event",
                1,
                payload.getBytes(StandardCharsets.UTF_8),
                Map.of("traceId", "t-1"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static PollRequest request(String group) {
        return new PollRequest(group, Set.of("test.topic"), 10, Duration.ofSeconds(30));
    }

    private ReceivedEvent pollOne(String group) {
        List<ReceivedEvent> events = store().poll(request(group));
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    @Test
    @DisplayName("领取到已发布消息，attempts=1")
    void pollReturnsPublishedMessage() {
        send(message("evt-1", "payload"));

        ReceivedEvent received = pollOne("g1");

        assertThat(received.message().eventId()).isEqualTo("evt-1");
        assertThat(new String(received.message().payload(), StandardCharsets.UTF_8))
                .isEqualTo("payload");
        assertThat(received.group()).isEqualTo("g1");
        assertThat(received.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("ack 之后同组不再重复投递")
    void ackPreventsRedelivery() {
        send(message("evt-1", "payload"));
        ReceivedEvent received = pollOne("g1");

        store().ack(received);

        assertThat(store().poll(request("g1"))).isEmpty();
    }

    @Test
    @DisplayName("不同消费者组各收到一份")
    void differentGroupsEachReceive() {
        send(message("evt-1", "payload"));

        ReceivedEvent first = pollOne("g1");
        store().ack(first);

        ReceivedEvent second = pollOne("g2");
        assertThat(second.message().eventId()).isEqualTo("evt-1");
        store().ack(second);

        assertThat(store().poll(request("g1"))).isEmpty();
        assertThat(store().poll(request("g2"))).isEmpty();
    }

    @Test
    @DisplayName("租约到期后消息可被重新领取，attempts 递增")
    void expiredLeaseRedelivers() throws Exception {
        send(message("evt-1", "payload"));
        PollRequest shortLease =
                new PollRequest("g1", Set.of("test.topic"), 10, Duration.ofMillis(150));
        List<ReceivedEvent> first = store().poll(shortLease);
        assertThat(first).hasSize(1);

        Thread.sleep(400);

        ReceivedEvent again = pollOne("g1");
        assertThat(again.message().eventId()).isEqualTo("evt-1");
        assertThat(again.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("retry 延迟期间不可领取，到期后重新投递")
    void retryDelayHidesMessage() throws Exception {
        send(message("evt-1", "payload"));
        ReceivedEvent received = pollOne("g1");

        store().retry(
                        received,
                        Duration.ofMillis(300),
                        new DeliveryFailure("java.io.IOException", "boom", true));

        assertThat(store().poll(request("g1"))).isEmpty();

        Thread.sleep(600);

        ReceivedEvent again = pollOne("g1");
        assertThat(again.message().eventId()).isEqualTo("evt-1");
        assertThat(again.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("死信之后同组不再投递，且可查询")
    void deadLetterStopsDelivery() {
        send(message("evt-1", "payload"));
        ReceivedEvent received = pollOne("g1");

        store().deadLetter(
                        received, new DeliveryFailure("java.lang.IllegalArgument", "bad", false));

        assertThat(store().poll(request("g1"))).isEmpty();
        List<DeadLetter> deadLetters = store().deadLetters("test.topic", "g1", 10);
        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.get(0).message().eventId()).isEqualTo("evt-1");
        assertThat(deadLetters.get(0).failure().errorType()).isEqualTo("java.lang.IllegalArgument");
    }

    @Test
    @DisplayName("poll 遵守 limit 与 topic 过滤")
    void pollHonoursLimitAndTopics() {
        send(message("evt-1", "a"));
        send(message("evt-2", "b"));
        EventMessage otherTopic =
                EventMessage.of(
                        "other-evt", "other.topic", "k", "c".getBytes(StandardCharsets.UTF_8));
        send(otherTopic);

        List<ReceivedEvent> events =
                store().poll(
                                new PollRequest(
                                        "g1", Set.of("test.topic"), 1, Duration.ofSeconds(30)));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).message().topic()).isEqualTo("test.topic");
    }

    @Test
    @DisplayName("使用失效凭证 ack 抛 LEASE_LOST")
    void staleDeliveryCannotAck() {
        send(message("evt-1", "payload"));
        ReceivedEvent received = pollOne("g1");
        ReceivedEvent stale =
                new ReceivedEvent(
                        UUID.randomUUID().toString(),
                        "g1",
                        received.message(),
                        received.attempts(),
                        received.leaseUntil());

        assertThatThrownBy(() -> store().ack(stale))
                .isInstanceOf(EventBusException.class)
                .extracting(e -> ((EventBusException) e).getErrorCode())
                .isEqualTo(EventBusErrorCode.LEASE_LOST);
    }

    @Test
    @DisplayName("close 幂等")
    void closeIsIdempotent() {
        store().close();
        store().close();
    }
}
