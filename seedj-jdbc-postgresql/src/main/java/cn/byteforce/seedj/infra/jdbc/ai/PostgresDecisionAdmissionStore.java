package cn.byteforce.seedj.infra.jdbc.ai;

import cn.byteforce.seedj.ai.DecisionAdmission;
import cn.byteforce.seedj.ai.spi.DecisionAdmissionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL 准入存储：单行插入 + {@code on conflict do nothing}，冲突时读回比较。
 *
 * <p>表由 {@link AiMigrations} V2 创建；本类不建表，也不做 DDL。过期清理是单条 {@code delete}，由调度器周期调用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresDecisionAdmissionStore implements DecisionAdmissionStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<List<String>> TARGETS = new TypeReference<>() {};

    private static final String INSERT =
            """
            INSERT INTO agent_decision_admission
                (decision_id, tenant_id, command_type, targets, args_hash, actor,
                 expires_at, admitted_at)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (decision_id) DO NOTHING
            """;

    private static final RowMapper<DecisionAdmission> ROW =
            (resultSet, rowNum) ->
                    new DecisionAdmission(
                            resultSet.getObject("decision_id", UUID.class),
                            resultSet.getObject("tenant_id", UUID.class),
                            resultSet.getString("command_type"),
                            readTargets(resultSet.getString("targets")),
                            resultSet.getString("args_hash"),
                            resultSet.getObject("actor", UUID.class),
                            resultSet.getTimestamp("expires_at").toInstant(),
                            resultSet.getTimestamp("admitted_at").toInstant());

    private final JdbcTemplate jdbc;

    /**
     * 绑定数据源。
     *
     * @param dataSource 已迁移到 {@code ai} scope 的数据源
     */
    public PostgresDecisionAdmissionStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void admit(DecisionAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        int inserted =
                jdbc.update(
                        INSERT,
                        admission.decisionId(),
                        admission.tenantId(),
                        admission.commandType(),
                        writeTargets(admission.targets()),
                        admission.argsHash(),
                        admission.actor(),
                        Timestamp.from(admission.expiresAt()),
                        Timestamp.from(admission.admittedAt()));
        if (inserted == 0) {
            DecisionAdmission existing =
                    find(admission.decisionId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "admission conflict but no row is readable: "
                                                            + admission.decisionId()));
            if (!same(existing, admission)) {
                throw new IllegalStateException(
                        "admission conflict: decision " + admission.decisionId());
            }
        }
    }

    @Override
    public Optional<DecisionAdmission> find(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");
        return jdbc
                .query(
                        "SELECT decision_id, tenant_id, command_type, targets, args_hash, actor,"
                                + " expires_at, admitted_at FROM agent_decision_admission"
                                + " WHERE decision_id = ?",
                        ROW,
                        decisionId)
                .stream()
                .findFirst();
    }

    @Override
    public int deleteExpired(Instant before) {
        Objects.requireNonNull(before, "before");
        return jdbc.update(
                "DELETE FROM agent_decision_admission WHERE expires_at < ?",
                Timestamp.from(before));
    }

    private static boolean same(DecisionAdmission left, DecisionAdmission right) {
        return left.tenantId().equals(right.tenantId())
                && left.commandType().equals(right.commandType())
                && left.targets().equals(right.targets())
                && left.argsHash().equals(right.argsHash())
                && left.actor().equals(right.actor());
    }

    private static String writeTargets(List<UUID> targets) {
        return JSON.writeValueAsString(targets.stream().map(UUID::toString).toList());
    }

    private static List<UUID> readTargets(String json) {
        return JSON.readValue(json, TARGETS).stream().map(UUID::fromString).toList();
    }
}
