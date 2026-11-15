package cn.byteforce.seedj.infra.coord;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.mq.v2.MqV2Client;
import cn.byteforce.coord.sdk.mq.v2.MqV2Publish;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicConfig;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicCreated;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicDescription;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.EventMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CoordEventBusTransportTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void publishesWithTopicEpochPartitionAndHeaders() throws Exception {
        MqV2Client mq = mock(MqV2Client.class);
        when(mq.describeTopic("orders")).thenReturn(description("orders", 7, 4));
        CoordEventBusTransport transport = new CoordEventBusTransport(mq, "svc", 4);

        transport.send(message("orders", "k")).toCompletableFuture().get(5, TimeUnit.SECONDS);

        ArgumentCaptor<MqV2Publish> captor = ArgumentCaptor.forClass(MqV2Publish.class);
        verify(mq).publish(captor.capture());
        MqV2Publish publish = captor.getValue();
        assertThat(publish.topic()).isEqualTo("orders");
        assertThat(publish.topicEpoch()).isEqualTo(7);
        assertThat(publish.partition()).isBetween(0, 3);
        assertThat(publish.idempotencyKey()).isEqualTo("evt-1");
        assertThat(publish.producer()).isEqualTo("svc");
        assertThat(new String(publish.key(), UTF_8)).isEqualTo("k");
        assertThat(new String(publish.payload(), UTF_8)).isEqualTo("{}");
        assertThat(publish.headers())
                .containsEntry("eventId", "evt-1")
                .containsEntry("eventType", "order.created")
                .containsEntry("eventVersion", "1")
                .containsEntry("occurredAt", OCCURRED_AT.toString())
                .containsEntry("traceparent", "trace-1");
    }

    @Test
    void createsMissingTopic() {
        MqV2Client mq = mock(MqV2Client.class);
        when(mq.describeTopic("new-topic"))
                .thenThrow(new CoordException(ErrorCode.TOPIC_NOT_FOUND, "missing"));
        when(mq.createTopic(eq("new-topic"), eq(4), anyString(), any()))
                .thenReturn(
                        new MqV2TopicCreated("new-topic", 1L, 9L, 4, MqV2TopicConfig.defaults()));
        CoordEventBusTransport transport = new CoordEventBusTransport(mq, "svc", 4);

        transport.ensureTopic("new-topic");

        verify(mq)
                .createTopic("new-topic", 4, "seedj:create:new-topic", MqV2TopicConfig.defaults());
    }

    @Test
    void topicExistsReturnsFalseOnNotFound() {
        MqV2Client mq = mock(MqV2Client.class);
        when(mq.describeTopic("missing"))
                .thenThrow(new CoordException(ErrorCode.TOPIC_NOT_FOUND, "missing"));
        CoordEventBusTransport transport = new CoordEventBusTransport(mq, "svc", 4);

        assertThat(transport.topicExists("missing")).isFalse();
    }

    @Test
    void refreshesEpochOnceOnStaleWrite() throws Exception {
        MqV2Client mq = mock(MqV2Client.class);
        when(mq.describeTopic("orders"))
                .thenReturn(description("orders", 7, 4), description("orders", 8, 4));
        when(mq.publish(any()))
                .thenThrow(new CoordException(ErrorCode.TOPIC_EPOCH_STALE, "stale"))
                .thenReturn(null);
        CoordEventBusTransport transport = new CoordEventBusTransport(mq, "svc", 4);

        transport.send(message("orders", "k")).toCompletableFuture().get(5, TimeUnit.SECONDS);

        ArgumentCaptor<MqV2Publish> captor = ArgumentCaptor.forClass(MqV2Publish.class);
        verify(mq, times(2)).publish(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(MqV2Publish::topicEpoch)
                .containsExactly(7L, 8L);
    }

    @Test
    void closeIsIdempotentAndRejectsFurtherSends() {
        CoordEventBusTransport transport =
                new CoordEventBusTransport(mock(MqV2Client.class), "svc", 4);
        transport.close();
        transport.close();

        assertThatThrownBy(
                        () -> transport.send(message("orders", "k")).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(EventBusException.class);
    }

    @Test
    void rejectsInvalidConfiguration() {
        MqV2Client mq = mock(MqV2Client.class);
        assertThatThrownBy(() -> new CoordEventBusTransport(mq, " ", 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoordEventBusTransport(mq, "svc", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoordEventBusTransport(mq, "svc", 4).ensureTopic(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EventMessage message(String topic, String partitionKey) {
        return new EventMessage(
                "evt-1",
                topic,
                partitionKey,
                "order.created",
                1,
                "{}".getBytes(UTF_8),
                Map.of("traceparent", "trace-1"),
                OCCURRED_AT);
    }

    private static MqV2TopicDescription description(String topic, long epoch, int partitionCount) {
        return new MqV2TopicDescription(
                topic, 1L, epoch, partitionCount, MqV2TopicConfig.defaults(), 0L, List.of());
    }
}
