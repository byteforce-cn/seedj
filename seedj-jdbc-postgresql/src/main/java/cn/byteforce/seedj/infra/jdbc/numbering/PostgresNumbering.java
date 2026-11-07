package cn.byteforce.seedj.infra.jdbc.numbering;

import cn.byteforce.seedj.numbering.Numbering;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * PostgreSQL 编号服务：每个 scope 一条独立 sequence。
 *
 * <p>首次使用某 scope 时执行 {@code CREATE SEQUENCE IF NOT EXISTS}；后续直接
 * {@code nextval}。多实例并发安全由数据库序列保证。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresNumbering implements Numbering {

    private static final Pattern SCOPE = Pattern.compile("^[a-z0-9_]{1,40}$");

    private final JdbcTemplate jdbc;

    private final Set<String> created = ConcurrentHashMap.newKeySet();

    /**
     * 构造编号服务。
     *
     * @param jdbc JDBC 模板
     */
    public PostgresNumbering(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public long next(String scope) {
        Objects.requireNonNull(scope, "scope");
        if (!SCOPE.matcher(scope).matches()) {
            throw new IllegalArgumentException("illegal numbering scope: " + scope);
        }
        String sequence = "numbering_" + scope;
        if (created.add(sequence)) {
            jdbc.execute("CREATE SEQUENCE IF NOT EXISTS " + sequence + " START 1");
        }
        Long value = jdbc.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
        if (value == null) {
            throw new IllegalStateException("sequence returned no value: " + sequence);
        }
        return value;
    }
}
