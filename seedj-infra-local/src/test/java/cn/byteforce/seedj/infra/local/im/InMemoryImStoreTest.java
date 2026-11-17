package cn.byteforce.seedj.infra.local.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImConversationKind;
import cn.byteforce.seedj.im.ImMember;
import cn.byteforce.seedj.im.ImMemberRole;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.ImMessageDraft;
import cn.byteforce.seedj.im.ImMessageKind;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 内存 IM 存储：会话、成员任期、消息、游标与序号。 */
class InMemoryImStoreTest {

    private static final Instant NOW = Instant.parse("2026-02-01T08:00:00Z");

    private final MutableClock clock = new MutableClock();
    private final ImSequenceAllocator numbers = new AtomicLongSequenceAllocator();

    private ImConversationStore conversations;
    private ImMessageStore messages;
    private ImCursorStore cursors;

    @BeforeEach
    void setUp() {
        conversations = new InMemoryImConversationStore(numbers, clock);
        messages = new InMemoryImMessageStore(clock);
        cursors = new InMemoryImCursorStore();
    }

    @Test
    @DisplayName("序号单调递增")
    void sequenceIsMonotonic() {
        long first = numbers.next();
        long second = numbers.next();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    @DisplayName("单聊会话按去重键唯一，重复创建报冲突")
    void directKeyIsUnique() {
        ImConversation single = singleConversation("u-1", "u-2");
        conversations.create(single);

        assertThat(conversations.findByDirectKey("default", single.directKey())).contains(single);
        assertThatThrownBy(() -> conversations.create(singleConversation("u-2", "u-1")))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    @DisplayName("同一会话 id 重复创建报冲突")
    void duplicateIdIsRejected() {
        ImConversation conversation = groupConversation();
        conversations.create(conversation);

        assertThatThrownBy(() -> conversations.create(conversation))
                .isInstanceOf(ContractException.class);
        assertThat(conversations.find(conversation.id())).contains(conversation);
        assertThat(conversations.find(null)).isEmpty();
        assertThat(conversations.findByDirectKey("default", null)).isEmpty();
    }

    @Test
    @DisplayName("成员加入是幂等的，退群保留任期")
    void membershipIsIdempotentAndKeepsTenures() {
        ImConversation conversation = groupConversation();
        conversations.create(conversation);

        conversations.addMember(conversation.id(), "u-1", ImMemberRole.OWNER, "u-1");
        conversations.addMember(conversation.id(), "u-2", ImMemberRole.MEMBER, "u-1");
        conversations.addMember(conversation.id(), "u-2", ImMemberRole.MEMBER, "u-1");

        assertThat(conversations.members(conversation.id()))
                .extracting(ImMember::subjectId)
                .containsExactly("u-1", "u-2");
        assertThat(conversations.member(conversation.id(), "u-2")).isPresent();

        long beforeLeave = numbers.next();
        conversations.removeMember(conversation.id(), "u-2", "u-1");
        conversations.removeMember(conversation.id(), "u-2", "u-1");
        long afterLeave = numbers.next();

        assertThat(conversations.member(conversation.id(), "u-2")).isEmpty();
        assertThat(conversations.members(conversation.id()))
                .extracting(ImMember::subjectId)
                .containsExactly("u-1");
        assertThat(conversations.membersAt(conversation.id(), beforeLeave))
                .extracting(ImMember::subjectId)
                .contains("u-2");
        assertThat(conversations.membersAt(conversation.id(), afterLeave))
                .extracting(ImMember::subjectId)
                .doesNotContain("u-2");
    }

    @Test
    @DisplayName("成员操作要求会话存在")
    void membershipRequiresConversation() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(
                        () -> conversations.addMember(missing, "u-1", ImMemberRole.MEMBER, "u-1"))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND);
        assertThatThrownBy(() -> conversations.removeMember(missing, "u-1", "u-1"))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> conversations.rename(missing, "x", "u-1"))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("主体的会话列表按最近更新排序，退群后消失")
    void conversationsOfSubjectFollowsMembership() {
        ImConversation first = groupConversation();
        ImConversation second = groupConversation();
        conversations.create(first);
        conversations.create(second);
        conversations.addMember(first.id(), "u-1", ImMemberRole.OWNER, "u-1");
        conversations.addMember(second.id(), "u-1", ImMemberRole.MEMBER, "u-1");

        clock.advance(Duration.ofMinutes(1));
        conversations.rename(second.id(), "新名字", "u-1");

        assertThat(conversations.conversationsOfSubject("default", "u-1"))
                .extracting(ImConversation::id)
                .containsExactly(second.id(), first.id());
        assertThat(conversations.pageOfSubject("default", "u-1", new PageRequest(1, 1)).total())
                .isEqualTo(2);
        assertThat(conversations.pageOfSubject("default", "u-1", new PageRequest(2, 1)).items())
                .extracting(ImConversation::id)
                .containsExactly(first.id());

        conversations.removeMember(second.id(), "u-1", "u-1");

        assertThat(conversations.conversationsOfSubject("default", "u-1"))
                .extracting(ImConversation::id)
                .containsExactly(first.id());
        assertThat(conversations.conversationsOfSubject("other", "u-1")).isEmpty();
    }

    @Test
    @DisplayName("消息按客户端幂等键去重，序号冲突报可重试错误")
    void messageAppendIsIdempotentByClientKey() {
        UUID conversationId = UUID.randomUUID();
        ImMessage first = messages.append(draft(conversationId, 1, "c-1"));
        ImMessage replay = messages.append(draft(conversationId, 2, "c-1"));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.seq()).isEqualTo(1);
        assertThat(messages.countAfter(conversationId, 0)).isEqualTo(1);

        assertThatThrownBy(() -> messages.append(draft(conversationId, 1, "c-2")))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.VERSION_CONFLICT);
    }

    @Test
    @DisplayName("消息分页、跨会话同步与游标")
    void messagePagesAndSync() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        messages.append(draft(first, 1, "a"));
        messages.append(draft(first, 3, "b"));
        messages.append(draft(second, 2, "c"));

        assertThat(messages.page(first, 0, 10)).extracting(ImMessage::seq).containsExactly(1L, 3L);
        assertThat(messages.page(first, 1, 10)).extracting(ImMessage::seq).containsExactly(3L);
        assertThat(messages.sync(List.of(first, second), 0, 10))
                .extracting(ImMessage::seq)
                .containsExactly(1L, 2L, 3L);
        assertThat(messages.sync(List.of(first), 1, 1))
                .extracting(ImMessage::seq)
                .containsExactly(3L);
        assertThat(messages.sync(List.of(), 0, 10)).isEmpty();
        assertThat(messages.sync(null, 0, 10)).isEmpty();
        assertThat(messages.maxSeq(first)).isEqualTo(3);
        assertThat(messages.maxSeq(UUID.randomUUID())).isZero();
        assertThat(messages.countAfter(first, 1)).isEqualTo(1);
        assertThat(messages.findByClientMsgId(first, "u-1", "b")).isPresent();
        assertThat(messages.findByClientMsgId(first, "u-1", null)).isEmpty();
        assertThat(messages.findByClientMsgId(first, "u-1", "missing")).isEmpty();
        assertThat(messages.find(messages.page(first, 0, 1).get(0).id())).isPresent();
        assertThat(messages.find(UUID.randomUUID())).isEmpty();
        assertThat(((InMemoryImMessageStore) messages).size()).isEqualTo(3);
    }

    @Test
    @DisplayName("编辑改正文但保留序号与创建时间，撤回置时间且幂等")
    void editAndRecallMessage() {
        UUID conversationId = UUID.randomUUID();
        ImMessage original = messages.append(draft(conversationId, 1, "c-1"));

        clock.advance(Duration.ofMinutes(1));
        ImMessage edited = messages.edit(original.id(), Map.of("text", "改过"));
        assertThat(edited.body()).containsEntry("text", "改过");
        assertThat(edited.seq()).isEqualTo(original.seq());
        assertThat(edited.createdAt()).isEqualTo(original.createdAt());
        assertThat(edited.editedAt()).isEqualTo(clock.instant());

        clock.advance(Duration.ofMinutes(1));
        ImMessage recalled = messages.recall(original.id());
        assertThat(recalled.recalledAt()).isEqualTo(clock.instant());
        assertThat(recalled.body()).containsEntry("text", "改过");
        assertThat(messages.page(conversationId, 0, 10)).containsExactly(recalled);

        clock.advance(Duration.ofMinutes(1));
        assertThat(messages.recall(original.id()).recalledAt()).isEqualTo(recalled.recalledAt());
    }

    @Test
    @DisplayName("已撤回消息不可编辑；编辑不存在或已撤回的消息被拒绝")
    void editRejectsRecalledAndMissing() {
        UUID conversationId = UUID.randomUUID();
        ImMessage message = messages.append(draft(conversationId, 1, "c-1"));
        messages.recall(message.id());

        assertThatThrownBy(() -> messages.edit(message.id(), Map.of("text", "x")))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.COMMAND_REJECTED);
        assertThatThrownBy(() -> messages.edit(UUID.randomUUID(), Map.of("text", "x")))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND);
        assertThatThrownBy(() -> messages.recall(UUID.randomUUID()))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("保留期删除只删游标之前的消息，并按上限分批")
    void deleteBeforeOnlyRemovesExpiredMessages() {
        UUID conversationId = UUID.randomUUID();
        ImMessage first = messages.append(draft(conversationId, 1, "c-1"));
        messages.append(draft(conversationId, 2, "c-2"));
        messages.append(draft(conversationId, 3, "c-3"));

        clock.advance(Duration.ofHours(2));
        ImMessage recent = messages.append(draft(conversationId, 4, "c-4"));
        Instant cutoff = clock.instant().minus(Duration.ofHours(1));

        assertThat(messages.deleteBefore(cutoff, 2)).isEqualTo(2);
        assertThat(messages.find(first.id())).isEmpty();
        assertThat(messages.page(conversationId, 0, 10))
                .extracting(ImMessage::id)
                .doesNotContain(first.id());
        assertThat(messages.deleteBefore(cutoff, 10)).isEqualTo(1);
        assertThat(messages.page(conversationId, 0, 10)).containsExactly(recent);
        assertThat(messages.deleteBefore(cutoff, 10)).isZero();
    }

    @Test
    @DisplayName("非正的分页上限是无效载荷")
    void nonPositiveLimitIsRejected() {
        UUID conversationId = UUID.randomUUID();

        assertThatThrownBy(() -> messages.page(conversationId, 0, 0))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
        assertThatThrownBy(() -> messages.sync(List.of(conversationId), 0, -1))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("游标只前进不后退")
    void cursorsOnlyMoveForward() {
        UUID conversationId = UUID.randomUUID();

        assertThat(cursors.readSeq(conversationId, "u-1")).isZero();
        assertThat(cursors.advanceRead(conversationId, "u-1", 10)).isEqualTo(10);
        assertThat(cursors.advanceRead(conversationId, "u-1", 4)).isEqualTo(10);
        assertThat(cursors.readSeq(conversationId, "u-1")).isEqualTo(10);

        assertThat(cursors.syncSeq("default", "u-1", "dev-1")).isZero();
        assertThat(cursors.advanceSync("default", "u-1", "dev-1", 7)).isEqualTo(7);
        assertThat(cursors.advanceSync("default", "u-1", "dev-1", 3)).isEqualTo(7);
        assertThat(cursors.syncSeq("default", "u-1", "dev-1")).isEqualTo(7);
        assertThat(cursors.syncSeq("default", "u-1", "dev-2")).isZero();
    }

    /** 可推进的测试时钟：固定时刻起步，按需前进。 */
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

    private static ImConversation singleConversation(String left, String right) {
        return new ImConversation(
                UUID.randomUUID(),
                "default",
                ImConversationKind.SINGLE,
                null,
                left,
                ImConversation.directKey(left, right),
                NOW,
                null);
    }

    private static ImConversation groupConversation() {
        return new ImConversation(
                UUID.randomUUID(),
                "default",
                ImConversationKind.GROUP,
                "研发",
                "u-1",
                null,
                NOW,
                null);
    }

    private static ImMessageDraft draft(UUID conversationId, long seq, String clientMsgId) {
        return new ImMessageDraft(
                UUID.randomUUID(),
                "default",
                conversationId,
                seq,
                "u-1",
                ImMessageKind.TEXT,
                Map.of("text", "hi"),
                clientMsgId);
    }
}
