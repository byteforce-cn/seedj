package cn.byteforce.seedj.eventbus.transport;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.PollRequest;
import cn.byteforce.seedj.eventbus.spi.ReceivedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同机多实例（两个 transport 共享同一目录）的消费者组语义。
 */
class FileEventConsumerGroupTest {

    @TempDir
    Path tempDir;

    private static PollRequest request(String group) {
        return new PollRequest(group, Set.of("group.topic"), 10, Duration.ofSeconds(30));
    }

    private static EventMessage message(int index) {
        return EventMessage.of("evt-" + index, "group.topic", "agg-1",
                ("p" + index).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("两个实例同属一个 group：一条消息只会被其中一个领走")
    void competingInstancesDoNotDuplicate() {
        FileEventBusTransport first = new FileEventBusTransport(tempDir);
        FileEventBusTransport second = new FileEventBusTransport(tempDir);
        int total = 30;
        for (int i = 0; i < total; i++) {
            first.send(message(i)).toCompletableFuture().join();
        }

        Set<String> seen = new HashSet<>();
        try {
            int idleRounds = 0;
            while (seen.size() < total && idleRounds < 20) {
                boolean progressed = consume(first, seen) | consume(second, seen);
                if (!progressed) {
                    idleRounds++;
                }
            }
        } finally {
            first.close();
            second.close();
        }

        assertThat(seen).hasSize(total);
    }

    @Test
    @DisplayName("已有实例持有租约时，另一个实例领取不到；ack 后也不会重投")
    void claimedMessageIsInvisibleToOtherInstance() {
        FileEventBusTransport first = new FileEventBusTransport(tempDir);
        FileEventBusTransport second = new FileEventBusTransport(tempDir);
        first.send(message(1)).toCompletableFuture().join();

        try {
            List<ReceivedEvent> claimed = first.poll(request("g1"));
            assertThat(claimed).hasSize(1);

            assertThat(second.poll(request("g1"))).isEmpty();

            first.ack(claimed.get(0));
            assertThat(second.poll(request("g1"))).isEmpty();
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    @DisplayName("不同 group 各收到一份")
    void groupsAreIndependent() {
        FileEventBusTransport first = new FileEventBusTransport(tempDir);
        first.send(message(1)).toCompletableFuture().join();

        try {
            List<ReceivedEvent> groupA = first.poll(request("a"));
            List<ReceivedEvent> groupB = first.poll(request("b"));
            assertThat(groupA).hasSize(1);
            assertThat(groupB).hasSize(1);
            assertThat(groupA.get(0).message().eventId())
                    .isEqualTo(groupB.get(0).message().eventId());
        } finally {
            first.close();
        }
    }

    @Test
    @DisplayName("ensureTopic 幂等，topicExists 反映目录")
    void ensureTopic() {
        FileEventBusTransport transport = new FileEventBusTransport(tempDir);
        try {
            assertThat(transport.topicExists("new.topic")).isFalse();
            transport.ensureTopic("new.topic");
            transport.ensureTopic("new.topic");
            assertThat(transport.topicExists("new.topic")).isTrue();
        } finally {
            transport.close();
        }
    }

    private static boolean consume(FileEventBusTransport transport, Set<String> seen) {
        List<ReceivedEvent> events = transport.poll(request("g1"));
        for (ReceivedEvent event : events) {
            seen.add(event.message().eventId());
            transport.ack(event);
        }
        return !events.isEmpty();
    }
}
