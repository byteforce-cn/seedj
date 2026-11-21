package cn.byteforce.seedj.read;

import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.SortDirection;
import java.util.Map;
import java.util.Objects;

/**
 * 一条自定义查询的执行计划：查询声明、命名参数与已解析的行范围。
 *
 * <p>网关完成校验、权限、脱敏与审计后把计划交给 {@link QueryExecutor}；SQL 方言、参数绑定与 谓词编译只存在于适配器，契约层不再生成 SQL。
 *
 * @param spec 查询声明
 * @param parameters 命名参数（只读）
 * @param sortField 已校验的排序字段
 * @param direction 排序方向
 * @param page 分页
 * @param scope 已解析的行范围谓词
 * @param schema 对象 schema（字段类型与列白名单）
 * @author ByteForce
 * @since 1.0.0
 */
public record QueryPlan(
        QuerySpec spec,
        Map<String, Object> parameters,
        String sortField,
        SortDirection direction,
        PageRequest page,
        ScopePredicate scope,
        ObjectSchema schema) {

    public QueryPlan {
        Objects.requireNonNull(spec, "spec");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        Objects.requireNonNull(sortField, "sortField");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(schema, "schema");
    }
}
