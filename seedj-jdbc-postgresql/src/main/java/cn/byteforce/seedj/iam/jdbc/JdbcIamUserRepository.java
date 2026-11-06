package cn.byteforce.seedj.iam.jdbc;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamPage;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.IamUserQuery;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** PostgreSQL user + credential store. */
public class JdbcIamUserRepository implements IamUserRepository {

    private static final RowMapper<IamUser> MAPPER = JdbcIamUserRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcIamUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IamUser> findById(String id) {
        List<IamUser> users = jdbc.query("SELECT * FROM iam_user WHERE id = ?", MAPPER, id);
        return users.stream().findFirst();
    }

    @Override
    public Optional<IamUser> findByUserName(String tenantId, String userName) {
        if (userName == null) {
            return Optional.empty();
        }
        List<IamUser> users = jdbc.query("""
                SELECT * FROM iam_user
                WHERE tenant_id = ? AND user_name_normalized = ?
                """, MAPPER, tenant(tenantId), userName.toLowerCase(Locale.ROOT));
        return users.stream().findFirst();
    }

    @Override
    public IamPage<IamUser> find(IamUserQuery query) {
        IamFilterSql.Sql filter = IamFilterSql.user(query.filter());
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM iam_user WHERE tenant_id = ? AND (%s)
                """.formatted(filter.where()), Long.class,
                prepend(tenant(query.tenantId()), filter.arguments()).toArray());
        List<Object> args = prepend(tenant(query.tenantId()), filter.arguments());
        args.add(query.count());
        args.add(query.startIndex() - 1);
        List<IamUser> users = jdbc.query("""
                SELECT * FROM iam_user
                WHERE tenant_id = ? AND (%s)
                ORDER BY user_name_normalized, id
                LIMIT ? OFFSET ?
                """.formatted(filter.where()), MAPPER, args.toArray());
        return new IamPage<>(users, total == null ? 0 : total.intValue(),
                query.startIndex(), users.size());
    }

    @Override
    public IamUser create(IamUser user, String passwordHash) {
        try {
            jdbc.update("""
                    INSERT INTO iam_user
                        (id, tenant_id, user_name, user_name_normalized, external_id,
                         display_name, email, active, attributes, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    """,
                    user.id(), user.tenantId(), user.userName(), user.normalizedUserName(),
                    user.externalId(), user.displayName(), user.email(), user.active(),
                    IamJdbcJson.map(user.attributes()), user.version(),
                    java.sql.Timestamp.from(user.createdAt()),
                    java.sql.Timestamp.from(user.updatedAt()));
        } catch (DuplicateKeyException e) {
            throw new IamException(IamErrorCode.USER_CONFLICT,
                    "user already exists: " + user.userName(), e);
        }
        if (passwordHash != null) {
            jdbc.update("""
                    INSERT INTO iam_user_credential (user_id, password_hash, password_changed_at)
                    VALUES (?, ?, now())
                    """, user.id(), passwordHash);
        }
        return user;
    }

    @Override
    public IamUser update(IamUser user) {
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE iam_user
                    SET user_name = ?, user_name_normalized = ?, external_id = ?, display_name = ?,
                        email = ?, active = ?, attributes = ?::jsonb, version = version + 1,
                        updated_at = now()
                    WHERE id = ? AND version = ?
                    """,
                    user.userName(), user.normalizedUserName(), user.externalId(),
                    user.displayName(), user.email(), user.active(),
                    IamJdbcJson.map(user.attributes()), user.id(), user.version());
        } catch (DuplicateKeyException e) {
            throw new IamException(IamErrorCode.USER_CONFLICT,
                    "user already exists: " + user.userName(), e);
        }
        if (updated == 0) {
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM iam_user WHERE id = ?)", Boolean.class, user.id()));
            throw new IamException(exists ? IamErrorCode.CONFLICT : IamErrorCode.USER_NOT_FOUND,
                    exists ? "user version conflict: " + user.id() : "user not found: " + user.id());
        }
        return findById(user.id()).orElseThrow();
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM iam_user WHERE id = ?", id);
    }

    @Override
    public Optional<String> findPasswordHash(String userId) {
        List<String> hashes = jdbc.queryForList(
                "SELECT password_hash FROM iam_user_credential WHERE user_id = ?",
                String.class, userId);
        return hashes.stream().findFirst();
    }

    @Override
    public void updatePassword(String userId, String passwordHash, Instant changedAt) {
        int updated = jdbc.update("""
                INSERT INTO iam_user_credential (user_id, password_hash, password_changed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET password_hash = EXCLUDED.password_hash,
                    password_changed_at = EXCLUDED.password_changed_at
                """, userId, passwordHash,
                java.sql.Timestamp.from(changedAt == null ? Instant.now() : changedAt));
        if (updated == 0) {
            throw new IamException(IamErrorCode.USER_NOT_FOUND, "user not found: " + userId);
        }
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM iam_user", Long.class);
        return count == null ? 0 : count;
    }

    private static IamUser map(ResultSet rs, int rowNum) throws SQLException {
        return new IamUser(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("user_name"),
                rs.getString("external_id"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getBoolean("active"),
                IamJdbcJson.map(rs.getString("attributes")),
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

    private static List<Object> prepend(Object first, List<Object> rest) {
        java.util.ArrayList<Object> all = new java.util.ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }
}
