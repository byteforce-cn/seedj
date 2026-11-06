package cn.byteforce.seedj.infra.jdbc.read;

import cn.byteforce.seedj.read.QueryExecutor;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PostgreSQL 的自定义查询执行器：只执行网关生成的 SQL 与位置参数。
 *
 * <p>不做任何条件拼接或改写；行范围、脱敏与审计已经在 {@code ReadGateway} 完成。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresQueryExecutor implements QueryExecutor {

    private final JdbcTemplate jdbc;

    /**
     * 构造执行器。
     *
     * @param jdbc JDBC 模板
     */
    public PostgresQueryExecutor(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<Map<String, Object>> page(String sql, List<Object> parameters) {
        return jdbc.queryForList(sql, parameters.toArray());
    }

    @Override
    public long count(String sql, List<Object> parameters) {
        Long total = jdbc.queryForObject(sql, Long.class, parameters.toArray());
        return total == null ? 0 : total;
    }
}
