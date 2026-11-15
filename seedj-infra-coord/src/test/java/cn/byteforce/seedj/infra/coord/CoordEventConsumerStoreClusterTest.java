package cn.byteforce.seedj.infra.coord;

import static cn.byteforce.seedj.infra.coord.CoordTestFixture.clusterEndpoint;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.DeliveryFailure;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.eventbus.spi.PollRequest;
import cn.byteforce.seedj.eventbus.spi.ReceivedEvent;
import cn.byteforce.seedj.infra.coord.fixture.CoordCluster;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 真集群：发布 → 领取 → retry → 重投 → deadLetter → 查询。 */
class CoordEventConsumerStoreClusterTest {

    private static final String TOPIC = "seedj.it.consumer";

    @Test
    void consumeRetryDeadLetterOnRealAgent() {
        try (CoordCluster cluster = CoordCluster.start()) {
            EventBusTransport transport =
                    new CoordEventBusTransport(
                            clusterEndpoint(cluster), cluster.client().mqV2(), "it", 1);
            CoordEventConsumerStore store = store(cluster);

            transport
                    .send(EventMessage.of("e-1", TOPIC, "k", bytes("first")))
                    .toCompletableFuture()
                    .join();

            ReceivedEvent first = pollOne(store, "g1");
            assertThat(first.message().eventId()).isEqualTo("e-1");
            assertThat(first.attempts()).isEqualTo(1);

            store.retry(first, Duration.ofMillis(500), failure());
            assertThat(store.poll(request("g1"))).isEmpty();
            sleep(900);

            ReceivedEvent again = pollOne(store, "g1");
            assertThat(again.message().eventId()).isEqualTo("e-1");
            assertThat(again.attempts()).isEqualTo(2);
            store.ack(again);
            assertThat(store.poll(request("g1"))).isEmpty();

            transport
                    .send(EventMessage.of("e-2", TOPIC, "k", bytes("second")))
                    .toCompletableFuture()
                    .join();
            ReceivedEvent poisoned = pollOne(store, "g1");
            store.deadLetter(poisoned, failure());

            assertThat(store.poll(request("g1"))).isEmpty();
            assertThat(store.deadLetters(TOPIC, "g1", 10))
                    .extracting(letter -> letter.message().eventId())
                    .containsExactly("e-2");
        }
    }

    private static CoordEventConsumerStore store(CoordCluster cluster) {
        return new CoordEventConsumerStore(
                new CoordKvStore(cluster.client().kv(), cluster.client().tx()),
                cluster.client().mqV2(),
                clusterEndpoint(cluster),
                CoordKeySpace.of("seedj:cluster-test", "event-consumer"),
                "cluster-member",
                0,
                1_000,
                1,
                Clock.systemUTC());
    }

    private static ReceivedEvent pollOne(CoordEventConsumerStore store, String group) {
        List<ReceivedEvent> events = store.poll(request(group));
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private static PollRequest request(String group) {
        return new PollRequest(group, Set.of(TOPIC), 10, Duration.ofSeconds(30));
    }

    private static DeliveryFailure failure() {
        return new DeliveryFailure("java.io.IOException", "boom", true);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
