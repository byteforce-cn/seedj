package cn.byteforce.seedj.infra.jdbc.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImConversationKind;
import cn.byteforce.seedj.im.ImMember;
import cn.byteforce.seedj.im.ImMemberRole;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL 会话存储：会话、成员任期与成员事件。
 *
 * <p>多语句方法在需要时开自己的事务；外层已有事务时按 {@code REQUIRED} 加入，因此 service 侧可以把 会话变更与 outbox 事件放在同一个事务里。
 *
 * <p>「同一会话里同一 subject 只有一段生效任期」由部分唯一索引 {@code uq_im_member_active} 保证， 而不是靠先查后写。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class JdbcImConversationStore implements ImConversationStore {

    private static final RowMapper<ImConversation> CONVERSATION_MAPPER =
            (rs, rowNum) ->
                    new ImConversation(
                            rs.getObject("id", UUID.class),
                            rs.getString("tenant_id"),
                            ImConversationKind.valueOf(rs.getString("kind")),
                            rs.getString("title"),
                            rs.getString("owner_id"),
                            rs.getString("direct_key"),
                            instant(rs, "created_at"),
                            instant(rs, "updated_at"));

    private static final RowMapper<ImMember> MEMBER_MAPPER =
            (rs, rowNum) ->
                    new ImMember(
                            rs.getObject("conversation_id", UUID.class),
                            rs.getString("subject_id"),
                            rs.getLong("joined_seq"),
                            rs.getObject("left_seq", Long.class),
                            ImMemberRole.valueOf(rs.getString("role")),
                            instant(rs, "muted_until"),
                            instant(rs, "joined_at"));

    private final JdbcTemplate jdbc;

    private final ImSequenceAllocator numbers;

    private final Clock clock;

    private final TransactionTemplate transaction;

    /**
     * 构造存储。
     *
     * @param jdbc JDBC 模板
     * @param numbers 序号分配器
     * @param clock 取时入口
     */
    public JdbcImConversationStore(JdbcTemplate jdbc, ImSequenceAllocator numbers, Clock clock) {
        this.jdbc = jdbc;
        this.numbers = numbers;
        this.clock = clock;
        DataSource dataSource = jdbc.getDataSource();
        this.transaction =
                dataSource == null
                        ? null
                        : new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public ImConversation create(ImConversation conversation) {
        return inTransaction(
                () -> {
                    try {
                        jdbc.update(
                                """
                                INSERT INTO im_conversation
                                    (id, tenant_id, kind, title, owner_id, direct_key,
                                     created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                                conversation.id(),
                                conversation.tenantId(),
                                conversation.kind().name(),
                                conversation.title(),
                                conversation.ownerId(),
                                conversation.directKey(),
                                timestamp(conversation.createdAt()),
                                timestamp(conversation.updatedAt()));
                    } catch (DuplicateKeyException duplicate) {
                        throw new ContractException(
                                ContractErrorCode.IDEMPOTENCY_CONFLICT,
                                "conversation already exists: " + conversation.directKey());
                    }
                    recordEvent(
                            conversation,
                            "created",
                            conversation.ownerId(),
                            Map.of("kind", conversation.kind().name()));
                    return conversation;
                });
    }

    @Override
    public Optional<ImConversation> find(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc
                .query("SELECT * FROM im_conversation WHERE id = ?", CONVERSATION_MAPPER, id)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ImConversation> findByDirectKey(String tenantId, String directKey) {
        if (directKey == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        "SELECT * FROM im_conversation WHERE tenant_id = ? AND direct_key = ?",
                        CONVERSATION_MAPPER,
                        tenantId,
                        directKey)
                .stream()
                .findFirst();
    }

    @Override
    public Page<ImConversation> pageOfSubject(String tenantId, String subjectId, PageRequest page) {
        Long total =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM im_conversation c
                        JOIN im_conversation_member m ON m.conversation_id = c.id
                        WHERE c.tenant_id = ? AND m.subject_id = ? AND m.left_seq IS NULL
                        """,
                        Long.class,
                        tenantId,
                        subjectId);
        List<ImConversation> items =
                jdbc.query(
                        """
                        SELECT c.* FROM im_conversation c
                        JOIN im_conversation_member m ON m.conversation_id = c.id
                        WHERE c.tenant_id = ? AND m.subject_id = ? AND m.left_seq IS NULL
                        ORDER BY c.updated_at DESC, c.id
                        LIMIT ? OFFSET ?
                        """,
                        CONVERSATION_MAPPER,
                        tenantId,
                        subjectId,
                        page.size(),
                        page.offset());
        return new Page<>(items, total == null ? 0 : total, page.page(), page.size());
    }

    @Override
    public List<ImConversation> conversationsOfSubject(String tenantId, String subjectId) {
        return jdbc.query(
                """
                SELECT c.* FROM im_conversation c
                JOIN im_conversation_member m ON m.conversation_id = c.id
                WHERE c.tenant_id = ? AND m.subject_id = ? AND m.left_seq IS NULL
                ORDER BY c.updated_at DESC, c.id
                """,
                CONVERSATION_MAPPER,
                tenantId,
                subjectId);
    }

    @Override
    public void rename(UUID conversationId, String title, String actorId) {
        inTransaction(
                () -> {
                    int updated =
                            jdbc.update(
                                    "UPDATE im_conversation SET title = ?, updated_at = ? WHERE id = ?",
                                    title,
                                    timestamp(clock.instant()),
                                    conversationId);
                    if (updated == 0) {
                        throw notFound(conversationId);
                    }
                    recordEvent(
                            require(conversationId),
                            "renamed",
                            actorId,
                            title == null ? Map.of() : Map.of("title", title));
                    return null;
                });
    }

    @Override
    public void addMember(
            UUID conversationId, String subjectId, ImMemberRole role, String actorId) {
        inTransaction(
                () -> {
                    ImConversation conversation = require(conversationId);
                    long joinedSeq = numbers.next();
                    int inserted =
                            jdbc.update(
                                    """
                                    INSERT INTO im_conversation_member
                                        (conversation_id, subject_id, joined_seq, left_seq,
                                         role, muted_until, joined_at)
                                    VALUES (?, ?, ?, NULL, ?, NULL, ?)
                                    ON CONFLICT (conversation_id, subject_id)
                                        WHERE left_seq IS NULL DO NOTHING
                                    """,
                                    conversationId,
                                    subjectId,
                                    joinedSeq,
                                    role.name(),
                                    timestamp(clock.instant()));
                    if (inserted == 0) {
                        return null;
                    }
                    touch(conversationId);
                    recordEvent(
                            conversation,
                            "member_added",
                            actorId,
                            Map.of("subject_id", subjectId, "role", role.name()));
                    return null;
                });
    }

    @Override
    public void removeMember(UUID conversationId, String subjectId, String actorId) {
        inTransaction(
                () -> {
                    ImConversation conversation = require(conversationId);
                    int updated =
                            jdbc.update(
                                    """
                                    UPDATE im_conversation_member SET left_seq = ?
                                    WHERE conversation_id = ? AND subject_id = ?
                                      AND left_seq IS NULL
                                    """,
                                    numbers.next(),
                                    conversationId,
                                    subjectId);
                    if (updated == 0) {
                        return null;
                    }
                    touch(conversationId);
                    recordEvent(
                            conversation,
                            "member_removed",
                            actorId,
                            Map.of("subject_id", subjectId));
                    return null;
                });
    }

    @Override
    public List<ImMember> members(UUID conversationId) {
        return jdbc.query(
                """
                SELECT * FROM im_conversation_member
                WHERE conversation_id = ? AND left_seq IS NULL
                ORDER BY joined_at, subject_id
                """,
                MEMBER_MAPPER,
                conversationId);
    }

    @Override
    public List<ImMember> membersAt(UUID conversationId, long seq) {
        return jdbc.query(
                """
                SELECT * FROM im_conversation_member
                WHERE conversation_id = ? AND joined_seq <= ?
                  AND (left_seq IS NULL OR left_seq > ?)
                ORDER BY joined_at, subject_id
                """,
                MEMBER_MAPPER,
                conversationId,
                seq,
                seq);
    }

    @Override
    public Optional<ImMember> member(UUID conversationId, String subjectId) {
        return jdbc
                .query(
                        """
                        SELECT * FROM im_conversation_member
                        WHERE conversation_id = ? AND subject_id = ? AND left_seq IS NULL
                        """,
                        MEMBER_MAPPER,
                        conversationId,
                        subjectId)
                .stream()
                .findFirst();
    }

    /**
     * 会话事件（诊断与审计用，不属于 SPI）。
     *
     * @param conversationId 会话标识
     * @return 事件视图：序号、类型、发起人
     */
    public List<Event> events(UUID conversationId) {
        return jdbc.query(
                """
                SELECT seq, type, actor_id FROM im_conversation_event
                WHERE conversation_id = ? ORDER BY seq
                """,
                (rs, rowNum) ->
                        new Event(
                                rs.getLong("seq"), rs.getString("type"), rs.getString("actor_id")),
                conversationId);
    }

    /**
     * 一条会话事件的只读视图。
     *
     * @param seq 序号
     * @param type 事件类型
     * @param actorId 发起人
     */
    public record Event(long seq, String type, String actorId) {}

    private void recordEvent(
            ImConversation conversation, String type, String actorId, Map<String, Object> payload) {
        jdbc.update(
                """
                INSERT INTO im_conversation_event
                    (id, tenant_id, conversation_id, seq, type, actor_id, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                UUID.randomUUID(),
                conversation.tenantId(),
                conversation.id(),
                numbers.next(),
                type,
                actorId,
                ImJdbcJson.write(payload),
                timestamp(clock.instant()));
    }

    private void touch(UUID conversationId) {
        jdbc.update(
                "UPDATE im_conversation SET updated_at = ? WHERE id = ?",
                timestamp(clock.instant()),
                conversationId);
    }

    private ImConversation require(UUID conversationId) {
        return find(conversationId).orElseThrow(() -> notFound(conversationId));
    }

    private static ContractException notFound(UUID conversationId) {
        return new ContractException(
                ContractErrorCode.OBJECT_NOT_FOUND, "conversation not found: " + conversationId);
    }

    private <T> T inTransaction(Supplier<T> work) {
        TransactionTemplate template = transaction;
        return template == null ? work.get() : template.execute(status -> work.get());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Object timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }
}
