package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import cn.byteforce.seedj.infra.local.im.AtomicLongSequenceAllocator;
import cn.byteforce.seedj.infra.local.im.InMemoryImConversationStore;
import cn.byteforce.seedj.infra.local.im.InMemoryImCursorStore;
import cn.byteforce.seedj.infra.local.im.InMemoryImMessageStore;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.starter.im.protocol.body.ChatDeliver;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 并发：同会话序号唯一、并发建单聊只产生一条会话。 */
class ImConcurrencyTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-01T08:00:00Z"), ZoneOffset.UTC);

    private final ImSequenceAllocator numbers = new AtomicLongSequenceAllocator();
    private final ImConversationStore conversations =
            new InMemoryImConversationStore(numbers, CLOCK);
    private final ImMessageStore messages = new InMemoryImMessageStore(CLOCK);
    private final ImCursorStore cursors = new InMemoryImCursorStore();
    private final ImConversationPolicy policy = new ImConversationPolicy() {};
    private final ImConversationService conversationService =
            new ImConversationService(conversations, (caller, peer) -> true, policy, CLOCK);
    private final ImMessagingService messaging =
            new ImMessagingService(
                    conversationService,
                    conversations,
                    messages,
                    cursors,
                    numbers,
                    new ImPushProvider() {
                        @Override
                        public void deliver(String subjectId, byte cmd, Object body) {}

                        @Override
                        public void deliverMany(
                                java.util.Collection<String> subjectIds, byte cmd, Object body) {}
                    },
                    policy,
                    TransactionRunner.direct(),
                    OutboxWriter.discard(),
                    ImProperties.defaults());

    @Test
    @DisplayName("同会话并发发送：序号互不相同，消息全部落库")
    void concurrentSendsGetDistinctSequences() throws Exception {
        ImConversation group =
                conversationService.createGroup(caller("u-1"), "压测", List.of("u-2", "u-3"));
        int threads = 8;
        int perThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        try {
            for (int t = 0; t < threads; t++) {
                String sender = "u-" + (t % 3 + 1);
                String clientId = "c-" + t + "-";
                pool.submit(
                        () -> {
                            try {
                                start.await(5, TimeUnit.SECONDS);
                                for (int i = 0; i < perThread; i++) {
                                    messaging.sendGroup(
                                            caller(sender),
                                            new ChatSendReq(
                                                    clientId + i,
                                                    group.id().toString(),
                                                    null,
                                                    "TEXT",
                                                    Map.of("i", i)));
                                }
                            } catch (Exception failure) {
                                failures.incrementAndGet();
                            } finally {
                                done.countDown();
                            }
                        });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).hasValue(0);
        List<ChatDeliver> stored = new ArrayList<>();
        messages.page(group.id(), 0, 1000)
                .forEach(message -> stored.add(messaging.toDeliver(message)));
        Set<Long> seqs = new HashSet<>();
        stored.forEach(deliver -> seqs.add(deliver.seq()));

        assertThat(stored).hasSize(threads * perThread);
        assertThat(seqs).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("并发对同一对人建单聊只产生一条会话")
    void concurrentDirectCreationYieldsOneConversation() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(
                        () -> {
                            try {
                                start.await(5, TimeUnit.SECONDS);
                                ids.add(
                                        conversationService
                                                .createSingle(caller("u-1"), "u-2")
                                                .id()
                                                .toString());
                            } catch (Exception ignored) {
                                ids.add("failed");
                            } finally {
                                done.countDown();
                            }
                        });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(ids).hasSize(1);
        assertThat(conversations.conversationsOfSubject("t1", "u-1")).hasSize(1);
        assertThat(conversations.conversationsOfSubject("t1", "u-2")).hasSize(1);
    }

    private static Caller caller(String id) {
        return new Caller(id, id, "t1", null, null, null, null, null, Map.of());
    }
}
