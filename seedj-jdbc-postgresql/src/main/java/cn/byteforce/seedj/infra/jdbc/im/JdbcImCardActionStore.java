package cn.byteforce.seedj.infra.jdbc.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.im.ImCardAction;
import cn.byteforce.seedj.im.spi.ImCardActionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL 卡片动作存储：{@code (message_id, action_id)} 主键 + {@code pending/done} 状态机。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class JdbcImCardActionStore implements ImCardActionStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;

    public JdbcImCardActionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Map<String, Object>> begin(ImCardAction action) {
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO im_card_action
                          (message_id, action_id, tenant_id, conversation_id, actor_id, state,
                           result, created_at)
                        VALUES (?, ?, ?, ?, ?, 'pending', NULL, ?)
                        ON CONFLICT (message_id, action_id) DO NOTHING
                        """,
                        action.messageId(),
                        action.actionId(),
                        action.tenantId(),
                        action.conversationId(),
                        action.actorId(),
                        Timestamp.from(Instant.now()));
        if (inserted == 1) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        "SELECT state, result FROM im_card_action WHERE message_id = ? AND"
                                + " action_id = ?",
                        (rs, rowNum) -> {
                            if (!"done".equals(rs.getString("state"))) {
                                throw new ContractException(
                                        ContractErrorCode.VERSION_CONFLICT,
                                        "card action in progress");
                            }
                            String json = rs.getString("result");
                            return json == null
                                    ? Map.<String, Object>of()
                                    : new LinkedHashMap<>(JSON.readValue(json, MAP));
                        },
                        action.messageId(),
                        action.actionId())
                .stream()
                .findFirst()
                .or(
                        () -> {
                            throw new ContractException(
                                    ContractErrorCode.VERSION_CONFLICT, "card action in progress");
                        });
    }

    @Override
    public void complete(ImCardAction action, Map<String, Object> result) {
        jdbc.update(
                """
                UPDATE im_card_action
                   SET state = 'done', result = ?::jsonb, completed_at = ?
                 WHERE message_id = ? AND action_id = ? AND state = 'pending'
                """,
                JSON.writeValueAsString(result == null ? Map.of() : result),
                Timestamp.from(Instant.now()),
                action.messageId(),
                action.actionId());
    }

    @Override
    public void abandon(ImCardAction action) {
        jdbc.update(
                "DELETE FROM im_card_action WHERE message_id = ? AND action_id = ? AND state ="
                        + " 'pending'",
                action.messageId(),
                action.actionId());
    }
}
