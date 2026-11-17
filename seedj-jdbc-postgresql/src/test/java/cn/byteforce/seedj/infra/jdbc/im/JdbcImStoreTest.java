package cn.byteforce.seedj.infra.jdbc.im;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL IM 存储契约：会话、任期、事件、消息、游标与序列。 */
@Testcontainers
class JdbcImStoreTest {

    private static final Instant NOW = Instant.parse("2026-02-01T08:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj_im")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private final MutableClock clock = new MutableClock();

    private JdbcTemplate jdbc;
    private JdbcImConversationStore conversations;
    private JdbcImMessageStore messages;
    private JdbcImCursorStore cursors;
    private ImSequenceAllocator numbers;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                DROP TABLE IF EXISTS im_message, im_read_cursor, im_sync_cursor,
                    im_conversation_event, im_conversation_member, im_conversation CASCADE
                """);
        jdbc.execute("DROP SEQUENCE IF EXISTS im_seq");
        jdbc.execute("DROP TABLE IF EXISTS seedj_schema_history");
        new ImSchemaCapability(new cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner(dataSource))
                .initialize();

        numbers = new JdbcImSequenceAllocator(jdbc);
        conversations = new JdbcImConversationStore(jdbc, numbers, clock);
        messages = new JdbcImMessageStore(jdbc, clock);
        cursors = new JdbcImCursorStore(jdbc, clock);
    }

    @Test
    @DisplayName("schema 能力先探针、再初始化、后校验，且可重复执行")
    void schemaCapabilityIsIdempotent() {
        ImSchemaCapability capability =
                new ImSchemaCapability(
                        new cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner(
                                jdbc.getDataSource()));

        assertThat(capability.isReady()).isTrue();
        capability.initialize();
        capability.validate();
        assertThat(capability.getName()).isEqualTo(ImSchemaCapability.NAME);
        assertThat(capability.getOrder()).isEqualTo(220);
        assertThat(capability.isRequired()).isTrue();
    }

    @Test
    @DisplayName("迁移可回滚到空 schema，再重新应用")
    void migrationsRollBackAndReapply() {
        cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner runner =
                new cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner(jdbc.getDataSource());

        runner.rollback(ImSchemaCapability.MIGRATION_SCOPE, ImMigrations.all(), 0);

        assertThat(regclass("im_conversation")).isNull();
        assertThat(regclass("im_message")).isNull();
        assertThat(regclass("im_read_cursor")).isNull();
        assertThat(regclass("im_seq")).isNull();

        runner.migrate(ImSchemaCapability.MIGRATION_SCOPE, ImMigrations.all());

        assertThat(regclass("im_conversation")).isNotNull();
        assertThat(regclass("im_message")).isNotNull();
        assertThat(regclass("im_read_cursor")).isNotNull();
        assertThat(regclass("im_seq")).isNotNull();
    }

    @Test
    @DisplayName("序列单调递增")
    void sequenceIsMonotonic() {
        long first = numbers.next();
        long second = numbers.next();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    @DisplayName("单聊去重键唯一，重复创建报冲突并留下 created 事件")
    void directKeyIsUnique() {
        ImConversation single = singleConversation("u-1", "u-2");
        conversations.create(single);

        assertThat(conversations.find(single.id())).contains(single);
        assertThat(conversations.findByDirectKey("default", single.directKey())).contains(single);
        assertThat(conversations.findByDirectKey("default", null)).isEmpty();
        assertThat(conversations.find(null)).isEmpty();
        assertThat(conversations.events(single.id()))
                .extracting(JdbcImConversationStore.Event::type)
                .containsExactly("created");

        assertThatThrownBy(() -> conversations.create(singleConversation("u-2", "u-1")))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.IDEMPOTENCY_CONFLICT);
        assertThatThrownBy(() -> conversations.create(single))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("改群名更新标题与时间，并留事件；未知会话报 404")
    void renameTouchesConversationAndEvents() {
        ImConversation conversation = groupConversation();
        conversations.create(conversation);
        clock.advance(Duration.ofMinutes(1));

        conversations.rename(conversation.id(), "新名字", "u-9");

        ImConversation renamed = conversations.find(conversation.id()).orElseThrow();
        assertThat(renamed.title()).isEqualTo("新名字");
        assertThat(renamed.updatedAt()).isAfter(conversation.updatedAt());
        assertThat(conversations.events(conversation.id()))
                .extracting(JdbcImConversationStore.Event::type)
                .containsExactly("created", "renamed");
        assertThat(conversations.events(conversation.id()).get(1).actorId()).isEqualTo("u-9");

        assertThatThrownBy(() -> conversations.rename(UUID.randomUUID(), "x", "u-9"))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("加人幂等、退群关闭任期、时点成员可查")
    void membershipTenuresAreQueryable() {
        ImConversation conversation = groupConversation();
        conversations.create(conversation);

        conversations.addMember(conversation.id(), "u-1", ImMemberRole.OWNER, "u-1");
        conversations.addMember(conversation.id(), "u-2", ImMemberRole.MEMBER, "u-1");
        conversations.addMember(conversation.id(), "u-2", ImMemberRole.ADMIN, "u-1");

        assertThat(conversations.members(conversation.id()))
                .extracting(ImMember::subjectId)
                .containsExactly("u-1", "u-2");
        assertThat(conversations.member(conversation.id(), "u-2"))
                .get()
                .extracting(ImMember::role)
                .isEqualTo(ImMemberRole.MEMBER);
        assertThat(conversations.events(conversation.id()))
                .extracting(JdbcImConversationStore.Event::type)
                .containsExactly("created", "member_added", "member_added");

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
        assertThat(conversations.events(conversation.id()))
                .extracting(JdbcImConversationStore.Event::type)
                .containsExactly("created", "member_added", "member_added", "member_removed");

        conversations.addMember(conversation.id(), "u-2", ImMemberRole.MEMBER, "u-1");
        assertThat(conversations.membersAt(conversation.id(), numbers.next()))
                .extracting(ImMember::subjectId)
                .contains("u-2");
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
    }

    @Test
    @DisplayName("会话列表按最近更新排序、分页，退群后消失")
    void conversationPagesFollowMembership() {
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
        assertThat(conversations.pageOfSubject("other", "u-1", PageRequest.defaults()).items())
                .isEmpty();

        conversations.removeMember(second.id(), "u-1", "u-1");

        assertThat(conversations.conversationsOfSubject("default", "u-1"))
                .extracting(ImConversation::id)
                .containsExactly(first.id());
    }

    @Test
    @DisplayName("消息幂等、序号冲突、分页与跨会话同步")
    void messageStoreBehavesLikeContract() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        ImMessage stored = messages.append(draft(first, 1, "c-1"));
        ImMessage replay = messages.append(draft(first, 2, "c-1"));
        assertThat(replay.id()).isEqualTo(stored.id());
        assertThat(replay.seq()).isEqualTo(1);
        assertThat(messages.find(stored.id())).contains(stored);
        assertThat(messages.find(UUID.randomUUID())).isEmpty();
        assertThat(messages.findByClientMsgId(first, "u-1", "c-1")).contains(stored);
        assertThat(messages.findByClientMsgId(first, "u-1", null)).isEmpty();

        assertThatThrownBy(() -> messages.append(draft(first, 1, "c-2")))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.VERSION_CONFLICT);

        messages.append(draft(first, 3, "c-3"));
        messages.append(draft(second, 2, "c-4"));

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
        assertThat(messages.page(first, 0, 1).get(0).body()).containsEntry("text", "hi");
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

    private String regclass(String name) {
        return jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, name);
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
