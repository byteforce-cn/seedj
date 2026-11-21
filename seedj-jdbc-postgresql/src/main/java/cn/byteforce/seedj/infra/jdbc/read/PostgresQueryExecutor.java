package cn.byteforce.seedj.infra.jdbc.read;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.infra.jdbc.domain.ScopeSql;
import cn.byteforce.seedj.read.QueryExecutor;
import cn.byteforce.seedj.read.QueryPlan;
import cn.byteforce.seedj.read.QuerySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 的自定义查询执行器：SQL 生成与执行都在适配器内。
 *
 * <p>网关只交来已校验的 {@link QueryPlan}；这里把命名参数绑定为位置参数，用 {@link ScopeSql}
 * 编译行范围（与对象表查询共用同一份谓词编译器），再套上排序与分页。范围字段必须出现在查询
 * 投影里，否则拒绝执行。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresQueryExecutor implements QueryExecutor {

    private static final Pattern NAMED_PARAMETER = Pattern.compile(":([a-z][a-z0-9_]*)");

    private final JdbcTemplate jdbc;

    /**
     * 构造执行器。
     *
     * @param jdbc JDBC 模板
     */
    public PostgresQueryExecutor(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 编译结果：分页 SQL、计数 SQL 与各自的参数。
     *
     * @param pageSql 分页 SQL
     * @param pageArguments 分页参数
     * @param countSql 计数 SQL
     * @param countArguments 计数参数
     */
    record CompiledQuery(
            String pageSql, List<Object> pageArguments, String countSql, List<Object> countArguments) {

        CompiledQuery {
            Objects.requireNonNull(pageSql, "pageSql");
            Objects.requireNonNull(countSql, "countSql");
            pageArguments = List.copyOf(pageArguments);
            countArguments = List.copyOf(countArguments);
        }
    }

    @Override
    public Page<Map<String, Object>> execute(QueryPlan plan) {
        Objects.requireNonNull(plan, "plan");
        CompiledQuery compiled = compile(plan);
        List<Map<String, Object>> rows =
                jdbc.queryForList(compiled.pageSql(), compiled.pageArguments().toArray());
        Long total =
                jdbc.queryForObject(
                        compiled.countSql(), Long.class, compiled.countArguments().toArray());
        return new Page<>(
                rows,
                total == null ? 0 : total,
                plan.page().page(),
                plan.page().size());
    }

    /**
     * 把执行计划编译成 SQL 与位置参数：命名参数绑定、行范围、排序与分页都只在这里发生。
     *
     * @param plan 查询计划
     * @return 编译结果
     */
    static CompiledQuery compile(QueryPlan plan) {
        Objects.requireNonNull(plan, "plan");
        List<Object> innerArguments = new ArrayList<>();
        String inner = bind(plan.spec(), plan.parameters(), innerArguments);
        ScopeSql.Clause scoped =
                ScopeSql.compile(plan.scope(), plan.schema(), plan.spec().fields());
        String from = "(" + inner + ") seedj_q WHERE " + scoped.sql();
        List<Object> scopeArguments = new ArrayList<>(innerArguments);
        scopeArguments.addAll(scoped.args());

        List<Object> pageArguments = new ArrayList<>(scopeArguments);
        pageArguments.add(plan.page().size());
        pageArguments.add(plan.page().offset());
        String pageSql =
                "SELECT * FROM "
                        + from
                        + " ORDER BY seedj_q.\""
                        + plan.sortField()
                        + "\" "
                        + plan.direction()
                        + " LIMIT ? OFFSET ?";
        return new CompiledQuery(
                pageSql, pageArguments, "SELECT count(*) FROM " + from, scopeArguments);
    }

    private static String bind(
            QuerySpec spec, Map<String, Object> values, List<Object> arguments) {
        String sql = spec.sql();
        if (sql.contains("?")) {
            throw invalidQuery("custom query must use named parameters, not '?'");
        }
        Matcher matcher = NAMED_PARAMETER.matcher(sql);
        StringBuilder out = new StringBuilder(sql.length() + 32);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!spec.parameters().contains(name)) {
                throw invalidQuery("undeclared parameter in query: " + name);
            }
            if (!values.containsKey(name)) {
                throw invalidQuery("missing parameter value: " + name);
            }
            arguments.add(values.get(name));
            matcher.appendReplacement(out, "?");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static ContractException invalidQuery(String message) {
        return new ContractException(ContractErrorCode.INVALID_PAYLOAD, message);
    }
}
