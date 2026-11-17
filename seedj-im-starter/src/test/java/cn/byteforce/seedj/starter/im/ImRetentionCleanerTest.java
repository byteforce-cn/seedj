package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.ImMessageDraft;
import cn.byteforce.seedj.im.ImMessageKind;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.infra.local.im.InMemoryImMessageStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 保留期清理：只删到期消息，按批循环，调度异常不外抛。 */
class ImRetentionCleanerTest {

    private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

    private final MutableClock clock = new MutableClock();
    private final ImMessageStore messages = new InMemoryImMessageStore(clock);

    @Test
    @DisplayName("没有配置 retention 时清理是空操作")
    void withoutRetentionNothingIsDeleted() {
        ImMessage message = messages.append(draft(1));
        clock.advance(Duration.ofDays(30));

        try (ImRetentionCleaner cleaner =
                new ImRetentionCleaner(messages, ImProperties.defaults(), clock)) {
            assertThat(cleaner.sweepOnce()).isZero();
        }
        assertThat(messages.find(message.id())).isPresent();
    }

    @Test
    @DisplayName("到期消息按批删除，未到期消息保留")
    void deletesExpiredMessagesInBatches() {
        ImMessage first = messages.append(draft(1));
        messages.append(draft(2));
        messages.append(draft(3));
        clock.advance(Duration.ofHours(2));
        ImMessage recent = messages.append(draft(4));

        ImProperties properties =
                new ImProperties(
                        true,
                        ImProperties.Store.MEMORY,
                        new ImProperties.Push("local", null),
                        new ImProperties.Tcp(false, 0),
                        new ImProperties.Ws(false, 0, "/ws"),
                        null,
                        0,
                        0,
                        null,
                        new ImProperties.Message(65_536, Duration.ofHours(1), null, null, 2),
                        null,
                        null,
                        null);

        try (ImRetentionCleaner cleaner = new ImRetentionCleaner(messages, properties, clock)) {
            assertThat(cleaner.sweepOnce()).isEqualTo(3);
        }

        assertThat(messages.find(first.id())).isEmpty();
        assertThat(messages.page(first.conversationId(), 0, 10)).containsExactly(recent);
    }

    private static ImMessageDraft draft(long seq) {
        return new ImMessageDraft(
                UUID.randomUUID(),
                "default",
                CONVERSATION,
                seq,
                "u-1",
                ImMessageKind.TEXT,
                Map.of("text", "hi"),
                "c-" + seq);
    }

    private static final UUID CONVERSATION =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 可推进的测试时钟。 */
    private static final class MutableClock extends Clock {

        private Instant now = NOW;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
