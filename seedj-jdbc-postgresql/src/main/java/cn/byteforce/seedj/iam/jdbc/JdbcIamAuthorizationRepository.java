package cn.byteforce.seedj.iam.jdbc;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** PostgreSQL RBAC store; inheritance is resolved with a recursive CTE. */
public class JdbcIamAuthorizationRepository implements IamAuthorizationRepository {

    private static final RowMapper<IamRole> ROLE_MAPPER = (rs, rowNum) -> new IamRole(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("parent_id"),
            rs.getBoolean("built_in"),
            rs.getLong("version"));

    private static final RowMapper<IamPermission> PERMISSION_MAPPER = (rs, rowNum) ->
            new IamPermission(
                    rs.getString("tenant_id"),
                    rs.getString("code"),
                    rs.getString("resource"),
                    rs.getString("action"),
                    rs.getString("description"));

    private final JdbcTemplate jdbc;

    public JdbcIamAuthorizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public IamRole createRole(IamRole role) {
        try {
            jdbc.update("""
                    INSERT INTO iam_role
                        (id, tenant_id, code, name, description, parent_id, built_in, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, role.id(), role.tenantId(), role.code(), role.name(), role.description(),
                    role.parentId(), role.builtIn(), role.version());
        } catch (DuplicateKeyException e) {
            throw new IamException(IamErrorCode.ROLE_CONFLICT,
                    "role already exists: " + role.code(), e);
        }
        bumpEpoch(role.tenantId());
        return role;
    }

    @Override
    public IamRole updateRole(IamRole role) {
        int updated = jdbc.update("""
                UPDATE iam_role
                SET code = ?, name = ?, description = ?, parent_id = ?, built_in = ?,
                    version = version + 1
                WHERE id = ? AND version = ?
                """, role.code(), role.name(), role.description(), role.parentId(),
                role.builtIn(), role.id(), role.version());
        if (updated == 0) {
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM iam_role WHERE id = ?)", Boolean.class, role.id()));
            throw new IamException(exists ? IamErrorCode.CONFLICT : IamErrorCode.ROLE_NOT_FOUND,
                    exists ? "role version conflict: " + role.id() : "role not found: " + role.id());
        }
        bumpEpoch(role.tenantId());
        return findRole(role.id()).orElseThrow();
    }

    @Override
    public void deleteRole(String roleId) {
        Optional<IamRole> role = findRole(roleId);
        jdbc.update("DELETE FROM iam_role WHERE id = ?", roleId);
        role.ifPresent(value -> bumpEpoch(value.tenantId()));
    }

    @Override
    public Optional<IamRole> findRole(String roleId) {
        return jdbc.query("SELECT * FROM iam_role WHERE id = ?", ROLE_MAPPER, roleId)
                .stream().findFirst();
    }

    @Override
    public Optional<IamRole> findRoleByCode(String tenantId, String code) {
        return jdbc.query("SELECT * FROM iam_role WHERE tenant_id = ? AND code = ?",
                ROLE_MAPPER, tenant(tenantId), code).stream().findFirst();
    }

    @Override
    public List<IamRole> findRoles(String tenantId) {
        return jdbc.query("SELECT * FROM iam_role WHERE tenant_id = ? ORDER BY code",
                ROLE_MAPPER, tenant(tenantId));
    }

    @Override
    public IamPermission savePermission(IamPermission permission) {
        jdbc.update("""
                INSERT INTO iam_permission (tenant_id, code, resource, action, description)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, code) DO UPDATE
                SET resource = EXCLUDED.resource, action = EXCLUDED.action,
                    description = EXCLUDED.description
                """, permission.tenantId(), permission.code(), permission.resource(),
                permission.action(), permission.description());
        bumpEpoch(permission.tenantId());
        return permission;
    }

    @Override
    public List<IamPermission> findPermissions(String tenantId) {
        return jdbc.query("SELECT * FROM iam_permission WHERE tenant_id = ? ORDER BY code",
                PERMISSION_MAPPER, tenant(tenantId));
    }

    @Override
    public void grantPermission(String roleId, String permissionCode) {
        jdbc.update("""
                INSERT INTO iam_role_permission (role_id, permission_code)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """, roleId, permissionCode);
        findRole(roleId).ifPresent(role -> bumpEpoch(role.tenantId()));
    }

    @Override
    public void revokePermission(String roleId, String permissionCode) {
        jdbc.update("DELETE FROM iam_role_permission WHERE role_id = ? AND permission_code = ?",
                roleId, permissionCode);
        findRole(roleId).ifPresent(role -> bumpEpoch(role.tenantId()));
    }

    @Override
    public Set<String> findPermissionCodes(Set<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(roleIds.size(), "?"));
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT permission_code FROM iam_role_permission
                WHERE role_id IN (%s) ORDER BY permission_code
                """.formatted(placeholders), String.class, roleIds.toArray()));
    }

    @Override
    public void assignRoleToUser(String userId, String roleId, Instant assignedAt, String assignedBy) {
        jdbc.update("""
                INSERT INTO iam_user_role (user_id, role_id, assigned_at, assigned_by)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, role_id) DO UPDATE
                SET assigned_at = EXCLUDED.assigned_at, assigned_by = EXCLUDED.assigned_by
                """, userId, roleId,
                java.sql.Timestamp.from(assignedAt == null ? Instant.now() : assignedAt),
                assignedBy);
        findRole(roleId).ifPresent(role -> bumpEpoch(role.tenantId()));
    }

    @Override
    public void unassignRoleFromUser(String userId, String roleId) {
        jdbc.update("DELETE FROM iam_user_role WHERE user_id = ? AND role_id = ?", userId, roleId);
        findRole(roleId).ifPresent(role -> bumpEpoch(role.tenantId()));
    }

    @Override
    public Set<String> findRoleIdsForUser(String userId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT role_id FROM iam_user_role WHERE user_id = ? ORDER BY role_id",
                String.class, userId));
    }

    @Override
    public void assignRoleToGroup(String groupId, String roleId) {
        jdbc.update("""
                INSERT INTO iam_group_role (group_id, role_id)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """, groupId, roleId);
        findRole(roleId).ifPresent(role -> bumpEpoch(role.tenantId()));
    }

    @Override
    public void unassignRoleFromGroup(String groupId, String roleId) {
        jdbc.update("DELETE FROM iam_group_role WHERE group_id = ? AND role_id = ?",
                groupId, roleId);
        findRole(roleId).ifPresent(role -> bumpEpoch(role.tenantId()));
    }

    @Override
    public Set<String> findRoleIdsForGroup(String groupId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT role_id FROM iam_group_role WHERE group_id = ? ORDER BY role_id",
                String.class, groupId));
    }

    @Override
    public Set<String> findRoleIdsWithAncestors(Set<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        String sql = """
                WITH RECURSIVE role_tree(id) AS (
                    SELECT unnest(?::varchar[])
                    UNION
                    SELECT r.parent_id FROM iam_role r
                    JOIN role_tree t ON r.id = t.id
                    WHERE r.parent_id IS NOT NULL
                )
                SELECT id FROM role_tree
                """;
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setArray(1, connection.createArrayOf("varchar", roleIds.toArray()));
            return statement;
        }, rs -> {
            Set<String> result = new LinkedHashSet<>();
            while (rs.next()) {
                result.add(rs.getString(1));
            }
            return result;
        });
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
