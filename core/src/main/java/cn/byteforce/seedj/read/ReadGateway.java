package cn.byteforce.seedj.read;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.core.security.ScopeContextHolder;
import cn.byteforce.seedj.core.utils.HashesHelper;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.security.AuthorizationRequest;
import cn.byteforce.seedj.security.AuthorizationSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 唯一读入口：对象级权限、行范围、字段策略与审计都在这里收口。
 *
 * <p>失败关闭：未知对象 404；未认证 401；无对象权限或无范围 resolver 403；
 * resolver 异常 503；resolver 返回空/未知字段、字段策略含未知字段 500。
 * 读范围在存储层下推，先过滤再分页与计数。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ReadGateway {

    private static final Logger LOG = LoggerFactory.getLogger(ReadGateway.class);

    private final ReadModelRegistry models;

    private final TemporalStore store;

    private final List<DataScopeResolver> scopes;

    private final List<FieldPolicyResolver> fieldPolicies;

    private final ReadAuditSink audit;

    private final Clock clock;

    private final IdGenerator<UUID> ids;

    private final QueryExecutor executor;

    private final AuthorizationDecisionPoint decisions;

    private static final java.util.regex.Pattern NAMED_PARAMETER =
            java.util.regex.Pattern.compile(":([a-z][a-z0-9_]*)");

    /**
     * 构造网关。
     *
     * @param models        读模型注册表
     * @param store         对象存储
     * @param scopes        行范围贡献者
     * @param fieldPolicies 字段策略贡献者
     * @param audit         读审计出口
     * @param clock         取时入口
     * @param ids           事件 id 生成器
     */
    public ReadGateway(ReadModelRegistry models, TemporalStore store,
                       List<DataScopeResolver> scopes, List<FieldPolicyResolver> fieldPolicies,
                       ReadAuditSink audit, Clock clock, IdGenerator<UUID> ids) {
        this(models, store, scopes, fieldPolicies, audit, clock, ids, null);
    }

    /**
     * 构造带自定义查询执行器的网关。
     *
     * @param models        读模型注册表
     * @param store         对象存储
     * @param scopes        行范围贡献者
     * @param fieldPolicies 字段策略贡献者
     * @param audit         读审计出口
     * @param clock         取时入口
     * @param ids           事件 id 生成器
     * @param executor      自定义查询执行器；{@code null} 表示未装配
     */
    public ReadGateway(ReadModelRegistry models, TemporalStore store,
                       List<DataScopeResolver> scopes, List<FieldPolicyResolver> fieldPolicies,
                       ReadAuditSink audit, Clock clock, IdGenerator<UUID> ids,
                       QueryExecutor executor) {
        this(models, store, scopes, fieldPolicies, audit, clock, ids, executor,
                callerPermissionDecisionPoint());
    }

    /**
     * 构造网关（生产装配）。
     *
     * @param models        读模型注册表
     * @param store         对象存储
     * @param scopes        行范围贡献者
     * @param fieldPolicies 字段策略贡献者
     * @param audit         读审计出口
     * @param clock         取时入口
     * @param ids           事件 id 生成器
     * @param executor      自定义查询执行器；{@code null} 表示未装配
     * @param decisions     唯一授权决策点
     */
    public ReadGateway(ReadModelRegistry models, TemporalStore store,
                       List<DataScopeResolver> scopes, List<FieldPolicyResolver> fieldPolicies,
                       ReadAuditSink audit, Clock clock, IdGenerator<UUID> ids,
                       QueryExecutor executor, AuthorizationDecisionPoint decisions) {
        this.executor = executor;
        this.models = Objects.requireNonNull(models, "models");
        this.store = Objects.requireNonNull(store, "store");
        this.scopes = sorted(scopes, DataScopeResolver::order);
        this.fieldPolicies = sorted(fieldPolicies, FieldPolicyResolver::order);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    /**
     * 旧装配兼容：只按调用方权限声明判断。生产装配必须传入真实决策点。
     */
    private static AuthorizationDecisionPoint callerPermissionDecisionPoint() {
        return request -> request.subject().permissions().contains(request.action())
                ? AuthorizationDecision.allow("caller-permissions")
                : AuthorizationDecision.deny("NO_PERMISSION",
                        "missing read permission: " + request.action(), "caller-permissions");
    }

    private static <T> List<T> sorted(List<T> values, java.util.function.ToIntFunction<T> order) {
        Objects.requireNonNull(values, "values");
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, "value"));
        }
        copy.sort(Comparator.comparingInt(order));
        return List.copyOf(copy);
    }

    /**
     * 单对象读取。
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     * @param query      时点与版本约束
     * @param caller     调用方
     * @return 渲染后的行；无命中或不在范围内为空
     */
    public Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query,
                                      Caller caller) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(query, "query");
        ReadModel model = requireReadable(objectType, caller, id.toString());
        ScopePredicate scope = resolveScope(model, caller);
        FieldPolicy policy = resolveFields(model, caller);
        Optional<TemporalRow> row = store.find(objectType, id, query, scope)
                .map(value -> render(value, policy, model.schema()));
        audit(ReadAccessEvent.Operation.FIND, model, caller, Map.of(),
                row.map(value -> value.version().id()).map(List::of).orElse(List.of()),
                scope, policy);
        return row;
    }

    /**
     * 列表查询。
     *
     * @param objectType 对象类型
     * @param search     查询条件
     * @param caller     调用方
     * @return 渲染后的页
     */
    public Page<TemporalRow> search(String objectType, TemporalSearch search, Caller caller) {
        ReadModel model = requireReadable(objectType, caller, null);
        Objects.requireNonNull(search, "search");
        if (!objectType.equals(search.objectType())) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "search objectType " + search.objectType() + " does not match " + objectType);
        }
        ScopePredicate scope = resolveScope(model, caller);
        FieldPolicy policy = resolveFields(model, caller);
        Page<TemporalRow> page = store.search(search, scope);
        List<TemporalRow> items = page.items().stream()
                .map(row -> render(row, policy, model.schema()))
                .toList();
        audit(ReadAccessEvent.Operation.SEARCH, model, caller, search.equals(), List.of(),
                scope, policy);
        return new Page<>(items, page.total(), page.page(), page.size(),
                page.nextCursor());
    }

    /**
     * 历史读取。
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     * @param caller     调用方
     * @return 渲染后的版本列表
     */
    public List<TemporalRow> history(String objectType, UUID id, Caller caller) {
        Objects.requireNonNull(id, "id");
        ReadModel model = requireReadable(objectType, caller, id.toString());
        ScopePredicate scope = resolveScope(model, caller);
        FieldPolicy policy = resolveFields(model, caller);
        List<TemporalRow> rows = store.history(objectType, id, scope).stream()
                .map(row -> render(row, policy, model.schema()))
                .toList();
        List<UUID> rowIds = rows.stream().map(row -> row.version().id()).distinct().toList();
        audit(ReadAccessEvent.Operation.HISTORY, model, caller, Map.of(), rowIds, scope, policy);
        return rows;
    }

    /**
     * 执行一条 reviewed SQL 查询，并在网关层注入行范围、字段策略与读审计。
     *
     * @param spec       查询声明
     * @param parameters 命名参数
     * @param sort       排序字段；空表示用 {@link QuerySpec#defaultSort()}
     * @param direction  排序方向；空表示升序
     * @param request    分页；空表示默认分页
     * @param caller     调用方
     * @return 渲染后的页
     */
    public Page<Map<String, Object>> query(QuerySpec spec, Map<String, Object> parameters,
                                           String sort, SortDirection direction,
                                           PageRequest request, Caller caller) {
        Objects.requireNonNull(spec, "spec");
        ReadModel model = requireReadable(spec.objectType(), caller, null);
        if (executor == null) {
            throw new ContractException(ContractErrorCode.UNAVAILABLE,
                    "custom query executor is not configured");
        }
        for (String field : spec.fields()) {
            if (model.schema().field(field).isEmpty()) {
                throw invalidQuery("unknown field for " + model.objectType() + ": " + field);
            }
        }
        for (String field : spec.sortableFields()) {
            FieldDefinition definition = model.schema().requireField(field);
            if (!definition.sortable() || definition.writeOnly()) {
                throw invalidQuery("field is not sortable: " + field);
            }
        }
        Map<String, Object> values = parameters == null ? Map.of() : parameters;
        for (String name : values.keySet()) {
            if (!spec.parameters().contains(name)) {
                throw invalidQuery("unknown parameter: " + name);
            }
        }
        String sortField = sort == null || sort.isBlank() ? spec.defaultSort() : sort;
        if (!spec.sortableFields().contains(sortField)) {
            throw invalidQuery("sort field is not allowed: " + sortField);
        }
        PageRequest pageRequest = request == null ? PageRequest.defaults() : request;
        SortDirection sortDirection = direction == null ? SortDirection.ASC : direction;

        ScopePredicate scope = resolveScope(model, caller);
        FieldPolicy policy = resolveFields(model, caller);
        if (scope instanceof ScopePredicate.None) {
            audit(ReadAccessEvent.Operation.SEARCH, model, caller, values, List.of(), scope,
                    policy);
            return new Page<>(List.of(), 0, pageRequest.page(), pageRequest.size());
        }

        List<Object> innerArguments = new ArrayList<>();
        String inner = bind(spec, values, innerArguments);
        List<Object> scopeArguments = new ArrayList<>();
        String scopeSql = scopeSql(scope, model.schema(), scopeArguments, spec.fields());
        String from = "(" + inner + ") seedj_q WHERE " + scopeSql;
        List<Object> pageArguments = new ArrayList<>(innerArguments);
        pageArguments.addAll(scopeArguments);
        pageArguments.add(pageRequest.size());
        pageArguments.add(pageRequest.offset());
        String pageSql = "SELECT * FROM " + from + " ORDER BY seedj_q.\"" + sortField + "\" "
                + sortDirection + " LIMIT ? OFFSET ?";

        List<Map<String, Object>> rows = executor.page(pageSql, pageArguments);
        long total = executor.count("SELECT count(*) FROM " + from,
                concat(innerArguments, scopeArguments));
        List<Map<String, Object>> rendered = rows.stream()
                .map(row -> renderQueryRow(row, policy, model.schema(), spec.fields()))
                .toList();
        audit(ReadAccessEvent.Operation.SEARCH, model, caller, values, List.of(), scope, policy);
        return new Page<>(rendered, total, pageRequest.page(), pageRequest.size());
    }

    private static ContractException invalidQuery(String message) {
        return new ContractException(ContractErrorCode.INVALID_PAYLOAD, message);
    }

    private static List<Object> concat(List<Object> first, List<Object> second) {
        List<Object> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    private static String bind(QuerySpec spec, Map<String, Object> values,
                               List<Object> arguments) {
        String sql = spec.sql();
        if (sql.contains("?")) {
            throw invalidQuery("custom query must use named parameters, not '?'");
        }
        java.util.regex.Matcher matcher = NAMED_PARAMETER.matcher(sql);
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

    private static Map<String, Object> renderQueryRow(Map<String, Object> row, FieldPolicy policy,
                                                      ObjectSchema schema,
                                                      Set<String> fields) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        for (String field : fields) {
            FieldDefinition definition = schema.requireField(field);
            FieldAccess access = policy.fields().get(field);
            if (definition.writeOnly() || access == null
                    || access.action() == FieldAccess.Action.HIDDEN) {
                continue;
            }
            if (!row.containsKey(field)) {
                throw new IllegalStateException(
                        "custom query did not return declared field: " + field);
            }
            Object value = row.get(field);
            if (access.action() == FieldAccess.Action.MASKED) {
                mask(definition, field, value, rendered);
            } else {
                rendered.put(field, value);
            }
        }
        return rendered;
    }

    private static String scopeSql(ScopePredicate predicate, ObjectSchema schema,
                                   List<Object> arguments, Set<String> projected) {
        return switch (predicate) {
            case ScopePredicate.All ignored -> "1=1";
            case ScopePredicate.None ignored -> "1=0";
            case ScopePredicate.Eq eq -> {
                requireProjected(eq.field(), projected);
                arguments.add(schema.requireField(eq.field()).type().coerce(eq.value()));
                yield column(eq.field()) + " = ?";
            }
            case ScopePredicate.In in -> {
                requireProjected(in.field(), projected);
                List<?> values = in.values();
                if (values.isEmpty()) {
                    yield "1=0";
                }
                StringBuilder sql = new StringBuilder(column(in.field())).append(" IN (");
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        sql.append(',');
                    }
                    sql.append('?');
                    arguments.add(schema.requireField(in.field()).type().coerce(values.get(i)));
                }
                yield sql.append(')').toString();
            }
            case ScopePredicate.NotNull notNull -> {
                requireProjected(notNull.field(), projected);
                yield column(notNull.field()) + " IS NOT NULL";
            }
            case ScopePredicate.And and -> {
                StringBuilder sql = new StringBuilder("(");
                for (int i = 0; i < and.parts().size(); i++) {
                    if (i > 0) {
                        sql.append(" AND ");
                    }
                    sql.append(scopeSql(and.parts().get(i), schema, arguments, projected));
                }
                yield sql.append(')').toString();
            }
            case ScopePredicate.Or or -> {
                StringBuilder sql = new StringBuilder("(");
                for (int i = 0; i < or.parts().size(); i++) {
                    if (i > 0) {
                        sql.append(" OR ");
                    }
                    sql.append(scopeSql(or.parts().get(i), schema, arguments, projected));
                }
                yield sql.append(')').toString();
            }
            case ScopePredicate.Not not -> "NOT ("
                    + scopeSql(not.part(), schema, arguments, projected) + ")";
        };
    }

    private static void requireProjected(String field, Set<String> projected) {
        if (!projected.contains(field)) {
            throw new IllegalStateException(
                    "row scope uses field " + field + " but the custom query does not project it");
        }
    }

    private static String column(String field) {
        return "seedj_q.\"" + field + "\"";
    }

    private ReadModel requireReadable(String objectType, Caller caller, String resourceId) {
        ReadModel model = models.find(objectType).orElseThrow(
                () -> new ContractException(ContractErrorCode.OBJECT_NOT_FOUND,
                        "unknown object type: " + objectType));
        if (caller == null) {
            throw new ContractException(ContractErrorCode.UNAUTHENTICATED,
                    "caller is required");
        }
        AuthorizationDecision decision = decisions.decide(new AuthorizationRequest(
                AuthorizationSubject.from(caller), model.readPermission(), model.objectType(),
                resourceId, Map.of(), clock.now(), Map.of()));
        if (decision == null) {
            throw new IllegalStateException(
                    "authorization decision point returned null for " + model.readPermission());
        }
        if (!decision.allowed()) {
            throw new ContractException(ContractErrorCode.FORBIDDEN, decision.reason(),
                    Map.of("reason_code", decision.reasonCode()), decision.reasonCode());
        }
        return model;
    }

    private ScopePredicate resolveScope(ReadModel model, Caller caller) {
        ScopeContext context = ScopeContextHolder.current().orElse(ScopeContext.empty());
        return ScopeResolvers.resolve(model.objectType(), caller, context, scopes,
                model.schema());
    }

    private static ContractException unavailable(String message, RuntimeException cause) {
        ContractException error = new ContractException(ContractErrorCode.UNAVAILABLE, message);
        error.initCause(cause);
        return error;
    }

    private static void field(ObjectSchema schema, String field) {
        if (schema.field(field).isEmpty()) {
            throw new IllegalStateException("field policy references unknown field: " + field);
        }
    }

    private FieldPolicy resolveFields(ReadModel model, Caller caller) {
        ScopeContext context = ScopeContextHolder.current().orElse(ScopeContext.empty());
        FieldPolicy grants = null;
        for (FieldPolicyResolver resolver : fieldPolicies) {
            if (!resolver.supports(model.objectType())) {
                continue;
            }
            FieldPolicy contribution;
            try {
                contribution = resolver.resolve(model.objectType(), caller, context);
            } catch (RuntimeException error) {
                throw unavailable("field policy resolution failed for " + model.objectType(),
                        error);
            }
            if (contribution == null) {
                throw new IllegalStateException(
                        "field policy resolver returned null for " + model.objectType());
            }
            for (String field : contribution.fields().keySet()) {
                field(model.schema(), field);
            }
            grants = grants == null ? contribution : grants.narrow(contribution);
        }
        if (grants == null) {
            return FieldPolicy.defaults(model.schema());
        }
        Map<String, FieldAccess> merged = new LinkedHashMap<>(
                FieldPolicy.defaults(model.schema()).fields());
        merged.putAll(grants.fields());
        return new FieldPolicy(merged);
    }

    private static TemporalRow render(TemporalRow row, FieldPolicy policy,
                                      ObjectSchema schema) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.fields().entrySet()) {
            String field = entry.getKey();
            FieldDefinition definition = schema.field(field).orElse(null);
            FieldAccess access = policy.fields().get(field);
            if (definition == null || definition.writeOnly() || access == null) {
                continue;
            }
            Object value = entry.getValue();
            switch (access.action()) {
                case FULL -> fields.put(field, value);
                case MASKED -> mask(definition, field, value, fields);
                case HIDDEN -> {
                }
            }
        }
        return new TemporalRow(row.objectType(), row.version(), fields);
    }

    private static void mask(FieldDefinition definition, String field, Object value,
                             Map<String, Object> fields) {
        if (definition.masker() != null) {
            fields.put(field, definition.masker().mask(field, value));
        }
    }

    private void audit(ReadAccessEvent.Operation operation, ReadModel model, Caller caller,
                       Map<String, Object> filters, List<UUID> rowIds, ScopePredicate scope,
                       FieldPolicy policy) {
        try {
            Set<String> policyIds = new LinkedHashSet<>();
            policy.fields().values().stream()
                    .map(FieldAccess::policyId)
                    .filter(Objects::nonNull)
                    .forEach(policyIds::add);
            audit.record(new ReadAccessEvent(ids.id(), caller.id(), model.objectType(),
                    operation, filters, rowIds, digest(scope, model.schema()), policyIds,
                    clock.now()));
        } catch (RuntimeException error) {
            LOG.error("read audit failed for {}", model.objectType(), error);
        }
    }

    private static String digest(ScopePredicate predicate, ObjectSchema schema) {
        StringBuilder text = new StringBuilder();
        canonical(predicate, schema, text);
        return HashesHelper.sha256Hex(text.toString());
    }

    private static void canonical(ScopePredicate predicate, ObjectSchema schema,
                                  StringBuilder out) {
        switch (predicate) {
            case ScopePredicate.All ignored -> out.append("all");
            case ScopePredicate.None ignored -> out.append("none");
            case ScopePredicate.Eq eq -> out.append("eq(").append(eq.field()).append(',')
                    .append(literal(schema.requireField(eq.field()).type().coerce(eq.value())))
                    .append(')');
            case ScopePredicate.In in -> {
                out.append("in(").append(in.field()).append(",[");
                boolean first = true;
                List<String> values = in.values().stream()
                        .map(value -> literal(schema.requireField(in.field()).type()
                                .coerce(value)))
                        .sorted()
                        .toList();
                for (String value : values) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(value);
                }
                out.append("])");
            }
            case ScopePredicate.NotNull notNull -> out.append("notNull(")
                    .append(notNull.field()).append(')');
            case ScopePredicate.And and -> composite("and", and.parts(), schema, out);
            case ScopePredicate.Or or -> composite("or", or.parts(), schema, out);
            case ScopePredicate.Not not -> {
                out.append("not(");
                canonical(not.part(), schema, out);
                out.append(')');
            }
        }
    }

    private static void composite(String name, List<ScopePredicate> parts, ObjectSchema schema,
                                  StringBuilder out) {
        out.append(name).append('(');
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            canonical(parts.get(i), schema, out);
        }
        out.append(')');
    }

    private static String literal(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        return value.getClass().getName() + ':' + text.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
