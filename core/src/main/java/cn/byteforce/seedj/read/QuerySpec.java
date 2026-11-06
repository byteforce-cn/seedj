package cn.byteforce.seedj.read;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 可审阅的自定义查询：单条 SELECT、命名参数、字段白名单、排序与分页。
 *
 * <p>SQL 由业务声明并随代码评审，网关只做三件事：把命名参数绑定为位置参数、在子查询外层
 * 注入行范围谓词、按 schema 的字段策略脱敏与审计。业务代码不再需要 {@code JdbcTemplate}
 * 绕过权限与脱敏。</p>
 *
 * <p>安全约束（构造期失败）：只允许单条 {@code SELECT}；不允许 {@code ;}、{@code --}、
 * {@code /*} 注释；字段名与参数名必须是标识符；排序字段必须同时出现在 {@link #fields()}
 * 与对象 schema 的 sortable 字段里。</p>
 *
 * @param objectType    主对象类型
 * @param sql           reviewed SQL（不含 {@code ORDER BY}/{@code LIMIT}）
 * @param fields        返回列白名单（必须是对象 schema 的字段）
 * @param parameters    命名参数白名单
 * @param sortableFields 允许排序的列（必须是 {@code fields} 的子集）
 * @param defaultSort   默认排序字段
 * @author ByteForce
 * @since 1.0.0
 */
public record QuerySpec(String objectType, String sql, Set<String> fields,
                        Set<String> parameters, List<String> sortableFields,
                        String defaultSort) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    public QuerySpec {
        if (objectType == null || objectType.isBlank()) {
            throw invalid("object type is required");
        }
        Objects.requireNonNull(sql, "sql");
        String normalized = sql.strip();
        if (!normalized.regionMatches(true, 0, "select ", 0, 7)) {
            throw invalid("query must be a single SELECT");
        }
        if (normalized.contains(";") || normalized.contains("--") || normalized.contains("/*")) {
            throw invalid("query must not contain statement separators or comments");
        }
        fields = copy(fields, "fields");
        if (fields.isEmpty()) {
            throw invalid("query must declare at least one field");
        }
        parameters = copy(parameters == null ? Set.of() : parameters, "parameters");
        sortableFields = sortableFields == null ? List.of() : List.copyOf(sortableFields);
        for (String field : fields) {
            identifier(field, "field");
        }
        for (String parameter : parameters) {
            identifier(parameter, "parameter");
        }
        for (String field : sortableFields) {
            if (!fields.contains(field)) {
                throw invalid("sortable field must be part of the field whitelist: " + field);
            }
        }
        if (sortableFields.isEmpty()) {
            throw invalid("query must declare at least one sortable field");
        }
        if (defaultSort == null || !sortableFields.contains(defaultSort)) {
            throw invalid("default sort must be one of the sortable fields");
        }
        sql = normalized;
    }

    private static Set<String> copy(Set<String> values, String role) {
        Objects.requireNonNull(values, role);
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static void identifier(String value, String role) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw invalid("illegal " + role + ": " + value);
        }
    }

    private static ContractException invalid(String message) {
        return new ContractException(ContractErrorCode.INVALID_PAYLOAD, message);
    }
}
