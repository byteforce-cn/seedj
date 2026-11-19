package cn.byteforce.seedj.infra.jdbc.ai;

import cn.byteforce.seedj.ai.ExternalEffect;
import cn.byteforce.seedj.ai.ExternalEffectState;
import cn.byteforce.seedj.ai.spi.ExternalEffectStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * PostgreSQL 外部效果存储：{@code on conflict do nothing} + 读回比较，完成态只更新不插入。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresExternalEffectStore implements ExternalEffectStore {

    private static final String INSERT =
            """
            INSERT INTO agent_external_effect
                (decision_id, tenant_id, command_type, args_hash, state,
                 external_ref, error_code, prepared_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (decision_id) DO NOTHING
            """;

    private static final RowMapper<ExternalEffect> ROW =
            (resultSet, rowNum) ->
                    new ExternalEffect(
                            resultSet.getObject("decision_id", UUID.class),
                            resultSet.getObject("tenant_id", UUID.class),
                            resultSet.getString("command_type"),
                            resultSet.getString("args_hash"),
                            ExternalEffectState.fromWire(resultSet.getString("state")),
                            resultSet.getString("external_ref"),
                            resultSet.getString("error_code"),
                            resultSet.getTimestamp("prepared_at").toInstant(),
                            resultSet.getTimestamp("completed_at") == null
                                    ? null
                                    : resultSet.getTimestamp("completed_at").toInstant());

    private final JdbcTemplate jdbc;

    public PostgresExternalEffectStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public ExternalEffect prepare(ExternalEffect effect) {
        Objects.requireNonNull(effect, "effect");
        int inserted =
                jdbc.update(
                        INSERT,
                        effect.decisionId(),
                        effect.tenantId(),
                        effect.commandType(),
                        effect.argsHash(),
                        effect.state().wire(),
                        effect.externalRef(),
                        effect.errorCode(),
                        Timestamp.from(effect.preparedAt()),
                        effect.completedAt() == null ? null : Timestamp.from(effect.completedAt()));
        if (inserted == 0) {
            ExternalEffect existing =
                    find(effect.decisionId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "external effect conflict but no row is"
                                                            + " readable: "
                                                            + effect.decisionId()));
            if (!same(existing, effect)) {
                throw new IllegalStateException(
                        "external effect conflict: decision " + effect.decisionId());
            }
            return existing;
        }
        return effect;
    }

    @Override
    public ExternalEffect complete(
            UUID decisionId,
            ExternalEffectState state,
            String externalRef,
            String errorCode,
            Instant now) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        int updated =
                jdbc.update(
                        """
                        UPDATE agent_external_effect
                           SET state = ?, external_ref = ?, error_code = ?, completed_at = ?
                         WHERE decision_id = ?
                        """,
                        state.wire(),
                        externalRef,
                        errorCode,
                        Timestamp.from(now),
                        decisionId);
        if (updated == 0) {
            throw new IllegalArgumentException("unknown external effect: " + decisionId);
        }
        return find(decisionId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "external effect disappeared: " + decisionId));
    }

    @Override
    public Optional<ExternalEffect> find(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");
        return jdbc
                .query(
                        """
                        SELECT decision_id, tenant_id, command_type, args_hash, state,
                               external_ref, error_code, prepared_at, completed_at
                          FROM agent_external_effect
                         WHERE decision_id = ?
                        """,
                        ROW,
                        decisionId)
                .stream()
                .findFirst();
    }

    private static boolean same(ExternalEffect left, ExternalEffect right) {
        return left.tenantId().equals(right.tenantId())
                && left.commandType().equals(right.commandType())
                && left.argsHash().equals(right.argsHash());
    }
}
