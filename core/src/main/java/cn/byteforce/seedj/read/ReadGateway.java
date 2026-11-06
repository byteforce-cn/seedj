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
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.id.IdGenerator;
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
        this.models = Objects.requireNonNull(models, "models");
        this.store = Objects.requireNonNull(store, "store");
        this.scopes = sorted(scopes, DataScopeResolver::order);
        this.fieldPolicies = sorted(fieldPolicies, FieldPolicyResolver::order);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
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
        ReadModel model = requireReadable(objectType, caller);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(query, "query");
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
        ReadModel model = requireReadable(objectType, caller);
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
        return new Page<>(items, page.total(), page.page(), page.size());
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
        ReadModel model = requireReadable(objectType, caller);
        Objects.requireNonNull(id, "id");
        ScopePredicate scope = resolveScope(model, caller);
        FieldPolicy policy = resolveFields(model, caller);
        List<TemporalRow> rows = store.history(objectType, id, scope).stream()
                .map(row -> render(row, policy, model.schema()))
                .toList();
        List<UUID> rowIds = rows.stream().map(row -> row.version().id()).distinct().toList();
        audit(ReadAccessEvent.Operation.HISTORY, model, caller, Map.of(), rowIds, scope, policy);
        return rows;
    }

    private ReadModel requireReadable(String objectType, Caller caller) {
        ReadModel model = models.find(objectType).orElseThrow(
                () -> new ContractException(ContractErrorCode.OBJECT_NOT_FOUND,
                        "unknown object type: " + objectType));
        if (caller == null) {
            throw new ContractException(ContractErrorCode.UNAUTHENTICATED,
                    "caller is required");
        }
        if (!caller.hasPermission(model.readPermission())) {
            throw new ContractException(ContractErrorCode.FORBIDDEN,
                    "missing read permission: " + model.readPermission());
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
