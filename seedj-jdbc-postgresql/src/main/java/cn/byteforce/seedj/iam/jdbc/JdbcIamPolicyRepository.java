package cn.byteforce.seedj.iam.jdbc;

import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.IamPolicyEffect;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PostgreSQL ABAC policy store. Conditions are JSONB. */
public class JdbcIamPolicyRepository implements IamPolicyRepository {

    private final JdbcTemplate jdbc;
    private final PolicyConditionCodec codec;
    private final RowMapper<IamPolicy> mapper;

    public JdbcIamPolicyRepository(JdbcTemplate jdbc) {
        this(jdbc, new PolicyConditionCodec());
    }

    public JdbcIamPolicyRepository(JdbcTemplate jdbc, PolicyConditionCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.mapper = (rs, rowNum) -> new IamPolicy(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                IamPolicyEffect.valueOf(rs.getString("effect")),
                rs.getInt("priority"),
                new LinkedHashSet<>(IamJdbcJson.strings(rs.getString("actions"))),
                new LinkedHashSet<>(IamJdbcJson.strings(rs.getString("resources"))),
                codec.decode(rs.getString("condition")),
                rs.getBoolean("enabled"),
                rs.getLong("version"));
    }

    @Override
    public IamPolicy save(IamPolicy policy) {
        IamPolicy current = findById(policy.id()).orElse(null);
        if (current != null && current.version() != policy.version()) {
            throw new cn.byteforce.seedj.iam.IamException(
                    cn.byteforce.seedj.iam.IamErrorCode.CONFLICT,
                    "policy version conflict: expected " + current.version());
        }
        jdbc.update("""
                INSERT INTO iam_policy
                    (id, tenant_id, name, effect, priority, actions, resources, condition,
                     enabled, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, now(), now())
                ON CONFLICT (id) DO UPDATE
                SET name = EXCLUDED.name, effect = EXCLUDED.effect,
                    priority = EXCLUDED.priority, actions = EXCLUDED.actions,
                    resources = EXCLUDED.resources, condition = EXCLUDED.condition,
                    enabled = EXCLUDED.enabled, version = iam_policy.version + 1,
                    updated_at = now()
                """, policy.id(), policy.tenantId(), policy.name(), policy.effect().name(),
                policy.priority(), IamJdbcJson.strings(policy.actions()),
                IamJdbcJson.strings(policy.resources()), codec.encode(policy.condition()),
                policy.enabled(), policy.version());
        bumpEpoch(policy.tenantId());
        return findById(policy.id()).orElse(policy);
    }

    @Override
    public Optional<IamPolicy> findById(String id) {
        return jdbc.query("SELECT * FROM iam_policy WHERE id = ?", mapper, id).stream().findFirst();
    }

    @Override
    public List<IamPolicy> findEnabled(String tenantId) {
        return jdbc.query("""
                SELECT * FROM iam_policy
                WHERE tenant_id = ? AND enabled = TRUE
                ORDER BY priority DESC, id
                """, mapper, tenant(tenantId));
    }

    @Override
    public List<IamPolicy> findAll(String tenantId) {
        return jdbc.query("""
                SELECT * FROM iam_policy WHERE tenant_id = ? ORDER BY name, id
                """, mapper, tenant(tenantId));
    }

    @Override
    public void delete(String id) {
        Optional<IamPolicy> policy = findById(id);
        jdbc.update("DELETE FROM iam_policy WHERE id = ?", id);
        policy.ifPresent(value -> bumpEpoch(value.tenantId()));
    }

    @Override
    public long currentEpoch(String tenantId) {
        List<Long> values = jdbc.queryForList(
                "SELECT epoch FROM iam_authz_epoch WHERE tenant_id = ?", Long.class, tenant(tenantId));
        return values.isEmpty() ? 0 : values.get(0);
    }

    @Override
    public void bumpEpoch(String tenantId) {
        jdbc.update("""
                INSERT INTO iam_authz_epoch (tenant_id, epoch) VALUES (?, 1)
                ON CONFLICT (tenant_id) DO UPDATE SET epoch = iam_authz_epoch.epoch + 1
                """, tenant(tenantId));
    }

    private static String tenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? cn.byteforce.seedj.iam.IamUser.DEFAULT_TENANT
                : tenantId;
    }
}
