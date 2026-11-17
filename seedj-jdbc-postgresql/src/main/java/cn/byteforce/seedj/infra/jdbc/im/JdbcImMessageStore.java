package cn.byteforce.seedj.infra.jdbc.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.ImMessageDraft;
import cn.byteforce.seedj.im.ImMessageKind;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL 消息存储：只追加、按序号读。
 *
 * <p>幂等靠唯一约束 {@code uq_im_message_client}，冲突时回读既有消息——先查后写会在并发重发时 造出两条消息。序号冲突（{@code
 * uq_im_message_seq}）映射成可重试的 {@code ContractException(VERSION_CONFLICT)}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class JdbcImMessageStore implements ImMessageStore {

    private static final RowMapper<ImMessage> MESSAGE_MAPPER =
            (rs, rowNum) ->
                    new ImMessage(
                            rs.getObject("id", UUID.class),
                            rs.getString("tenant_id"),
                            rs.getObject("conversation_id", UUID.class),
                            rs.getLong("seq"),
                            rs.getString("sender_id"),
                            ImMessageKind.valueOf(rs.getString("kind")),
                            ImJdbcJson.read(rs.getString("body")),
                            rs.getString("client_msg_id"),
                            instant(rs, "created_at"),
                            instant(rs, "edited_at"),
                            instant(rs, "recalled_at"));

    private final JdbcTemplate jdbc;

    private final Clock clock;

    private final TransactionTemplate transaction;

    /**
     * 构造存储。
     *
     * @param jdbc JDBC 模板
     * @param clock 取时入口
     */
    public JdbcImMessageStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        DataSource dataSource = jdbc.getDataSource();
        this.transaction =
                dataSource == null
                        ? null
                        : new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public ImMessage append(ImMessageDraft draft) {
        return inTransaction(
                () -> {
                    try {
                        jdbc.update(
                                """
                                INSERT INTO im_message
                                    (id, tenant_id, conversation_id, seq, sender_id, kind, body,
                                     client_msg_id, created_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                                ON CONFLICT ON CONSTRAINT uq_im_message_client DO NOTHING
                                """,
                                draft.id(),
                                draft.tenantId(),
                                draft.conversationId(),
                                draft.seq(),
                                draft.senderId(),
                                draft.kind().name(),
                                ImJdbcJson.write(draft.body()),
                                draft.clientMsgId(),
                                timestamp(clock.instant()));
                    } catch (DuplicateKeyException conflict) {
                        throw new ContractException(
                                ContractErrorCode.VERSION_CONFLICT,
                                "message sequence already used in conversation: " + draft.seq());
                    }
                    return find(draft.id())
                            .or(
                                    () ->
                                            draft.clientMsgId() == null
                                                    ? Optional.empty()
                                                    : findByClientMsgId(
                                                            draft.conversationId(),
                                                            draft.senderId(),
                                                            draft.clientMsgId()))
                            .orElseThrow(
                                    () ->
                                            new ContractException(
                                                    ContractErrorCode.INTERNAL_ERROR,
                                                    "message insert did not produce a row"));
                });
    }

    @Override
    public Optional<ImMessage> find(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM im_message WHERE id = ?", MESSAGE_MAPPER, id).stream()
                .findFirst();
    }

    @Override
    public Optional<ImMessage> findByClientMsgId(
            UUID conversationId, String senderId, String clientMsgId) {
        if (clientMsgId == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        """
                        SELECT * FROM im_message
                        WHERE conversation_id = ? AND sender_id = ? AND client_msg_id = ?
                        """,
                        MESSAGE_MAPPER,
                        conversationId,
                        senderId,
                        clientMsgId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ImMessage> page(UUID conversationId, long afterSeq, int limit) {
        return jdbc.query(
                """
                SELECT * FROM im_message
                WHERE conversation_id = ? AND seq > ?
                ORDER BY seq
                LIMIT ?
                """,
                MESSAGE_MAPPER,
                conversationId,
                afterSeq,
                requireLimit(limit));
    }

    @Override
    public List<ImMessage> sync(List<UUID> conversationIds, long afterSeq, int limit) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(conversationIds.size(), "?"));
        List<Object> args = new ArrayList<>(conversationIds);
        args.add(afterSeq);
        args.add(requireLimit(limit));
        return jdbc.query(
                "SELECT * FROM im_message WHERE conversation_id IN ("
                        + placeholders
                        + ") AND seq > ? ORDER BY seq LIMIT ?",
                MESSAGE_MAPPER,
                args.toArray());
    }

    @Override
    public long maxSeq(UUID conversationId) {
        Long value =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(seq), 0) FROM im_message WHERE conversation_id = ?",
                        Long.class,
                        conversationId);
        return value == null ? 0L : value;
    }

    @Override
    public long countAfter(UUID conversationId, long afterSeq) {
        Long value =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM im_message WHERE conversation_id = ? AND seq > ?",
                        Long.class,
                        conversationId,
                        afterSeq);
        return value == null ? 0L : value;
    }

    @Override
    public ImMessage edit(UUID id, Map<String, Object> body) {
        ImMessage current = require(id);
        if (current.recalledAt() != null) {
            throw rejected(id);
        }
        try {
            ImMessage updated =
                    jdbc.queryForObject(
                            """
                            UPDATE im_message SET body = ?::jsonb, edited_at = ?
                            WHERE id = ? AND recalled_at IS NULL
                            RETURNING *
                            """,
                            MESSAGE_MAPPER,
                            ImJdbcJson.write(body),
                            timestamp(clock.instant()),
                            id);
            return require(updated);
        } catch (EmptyResultDataAccessException raced) {
            ImMessage reread = require(id);
            if (reread.recalledAt() != null) {
                throw rejected(id);
            }
            throw raced;
        }
    }

    @Override
    public ImMessage recall(UUID id) {
        try {
            ImMessage updated =
                    jdbc.queryForObject(
                            """
                            UPDATE im_message SET recalled_at = ?
                            WHERE id = ? AND recalled_at IS NULL
                            RETURNING *
                            """,
                            MESSAGE_MAPPER,
                            timestamp(clock.instant()),
                            id);
            return require(updated);
        } catch (EmptyResultDataAccessException raced) {
            return require(id);
        }
    }

    @Override
    public int deleteBefore(Instant cutoff, int limit) {
        if (cutoff == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "cutoff 不能为空");
        }
        return jdbc.update(
                """
                DELETE FROM im_message WHERE id IN (
                    SELECT id FROM im_message WHERE created_at < ? ORDER BY created_at LIMIT ?
                )
                """,
                timestamp(cutoff),
                requireLimit(limit));
    }

    private ImMessage require(UUID id) {
        return find(id)
                .orElseThrow(
                        () ->
                                new ContractException(
                                        ContractErrorCode.OBJECT_NOT_FOUND,
                                        "message not found: " + id));
    }

    private static ImMessage require(ImMessage message) {
        if (message == null) {
            throw new ContractException(
                    ContractErrorCode.INTERNAL_ERROR, "message update returned no row");
        }
        return message;
    }

    private static ContractException rejected(UUID id) {
        return new ContractException(
                ContractErrorCode.COMMAND_REJECTED, "已撤回的消息不能编辑: " + id);
    }

    private static int requireLimit(int limit) {
        if (limit <= 0) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "limit must be positive: " + limit);
        }
        return limit;
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
