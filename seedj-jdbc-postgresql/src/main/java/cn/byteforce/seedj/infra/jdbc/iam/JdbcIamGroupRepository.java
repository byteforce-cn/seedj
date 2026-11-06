package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamGroupQuery;
import cn.byteforce.seedj.iam.IamPage;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** PostgreSQL SCIM group store. Membership writes are transactional. */
public class JdbcIamGroupRepository implements IamGroupRepository {

    private static final RowMapper<IamGroup> MAPPER = JdbcIamGroupRepository::map;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcIamGroupRepository(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public Optional<IamGroup> findById(String id) {
        return jdbc.query("SELECT * FROM iam_group WHERE id = ?", MAPPER, id).stream()
                .findFirst().map(this::withMembers);
    }

    @Override
    public Optional<IamGroup> findByDisplayName(String tenantId, String displayName) {
        if (displayName == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT * FROM iam_group
                WHERE tenant_id = ? AND lower(display_name) = lower(?)
                """, MAPPER, tenant(tenantId), displayName).stream()
                .findFirst().map(this::withMembers);
    }

    @Override
    public IamPage<IamGroup> find(IamGroupQuery query) {
        IamFilterSql.Sql filter = IamFilterSql.group(query.filter());
        List<Object> base = new ArrayList<>();
        base.add(tenant(query.tenantId()));
        base.addAll(filter.arguments());
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM iam_group WHERE tenant_id = ? AND (%s)
                """.formatted(filter.where()), Long.class, base.toArray());

        List<Object> args = new ArrayList<>(base);
        args.add(query.count());
        args.add(query.startIndex() - 1);
        List<IamGroup> groups = jdbc.query("""
                SELECT * FROM iam_group
                WHERE tenant_id = ? AND (%s)
                ORDER BY lower(display_name), id
                LIMIT ? OFFSET ?
                """.formatted(filter.where()), MAPPER, args.toArray()).stream()
                .map(this::withMembers).toList();
        return new IamPage<>(groups, total == null ? 0 : total.intValue(),
                query.startIndex(), groups.size());
    }

    @Override
    public IamGroup create(IamGroup group) {
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO iam_group
                            (id, tenant_id, display_name, external_id, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, group.id(), group.tenantId(), group.displayName(), group.externalId(),
                        group.version(), java.sql.Timestamp.from(group.createdAt()),
                        java.sql.Timestamp.from(group.updatedAt()));
                for (String memberId : group.memberIds()) {
                    jdbc.update("""
                            INSERT INTO iam_group_member (group_id, user_id)
                            VALUES (?, ?) ON CONFLICT DO NOTHING
                            """, group.id(), memberId);
                }
            });
        } catch (DuplicateKeyException e) {
            throw new IamException(IamErrorCode.GROUP_CONFLICT,
                    "group already exists: " + group.displayName(), e);
        }
        return group;
    }

    @Override
    public IamGroup update(IamGroup group) {
        try {
            transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    UPDATE iam_group
                    SET display_name = ?, external_id = ?, version = version + 1, updated_at = now()
                    WHERE id = ? AND version = ?
                    """, group.displayName(), group.externalId(), group.id(), group.version());
            if (updated == 0) {
                boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM iam_group WHERE id = ?)",
                        Boolean.class, group.id()));
                throw new IamException(exists ? IamErrorCode.CONFLICT : IamErrorCode.GROUP_NOT_FOUND,
                        exists ? "group version conflict: " + group.id()
                                : "group not found: " + group.id());
            }
            jdbc.update("DELETE FROM iam_group_member WHERE group_id = ?", group.id());
            for (String memberId : group.memberIds()) {
                    jdbc.update("""
                            INSERT INTO iam_group_member (group_id, user_id)
                            VALUES (?, ?) ON CONFLICT DO NOTHING
                            """, group.id(), memberId);
                }
            });
        } catch (DuplicateKeyException e) {
            throw new IamException(IamErrorCode.GROUP_CONFLICT,
                    "group already exists: " + group.displayName(), e);
        }
        return findById(group.id()).orElseThrow();
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM iam_group WHERE id = ?", id);
    }

    @Override
    public Set<String> findMemberIds(String groupId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT user_id FROM iam_group_member WHERE group_id = ? ORDER BY user_id",
                String.class, groupId));
    }

    @Override
    public Set<String> findGroupIdsForUser(String userId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT group_id FROM iam_group_member WHERE user_id = ? ORDER BY group_id
                """, String.class, userId));
    }

    @Override
    public void addMember(String groupId, String userId) {
        jdbc.update("""
                INSERT INTO iam_group_member (group_id, user_id)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """, groupId, userId);
        touch(groupId);
    }

    @Override
    public void removeMember(String groupId, String userId) {
        jdbc.update("DELETE FROM iam_group_member WHERE group_id = ? AND user_id = ?",
                groupId, userId);
        touch(groupId);
    }

    private IamGroup withMembers(IamGroup group) {
        return group.withMembers(findMemberIds(group.id()));
    }

    private void touch(String groupId) {
        jdbc.update("UPDATE iam_group SET version = version + 1, updated_at = now() WHERE id = ?",
                groupId);
    }

    private static IamGroup map(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        Set<String> members = new LinkedHashSet<>(); // filled by findById/find after mapping
        return new IamGroup(
                id,
                rs.getString("tenant_id"),
                rs.getString("display_name"),
                rs.getString("external_id"),
                members,
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String tenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
    }
}
