package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件溯源聚合的写范围校验：把谓词引用的 schema 字段映射到聚合访问器。
 *
 * <p>约定：{@code snake_case} 字段 {@code department} 对应 {@code department()}、 {@code getDepartment()} 或
 * {@code isDepartment()}。只解析谓词实际引用的字段； {@link ScopePredicate.All} 不触发任何反射。访问器缺失是部署错误，失败关闭而不是放行。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class AggregateScope {

    private static final Map<Class<?>, Map<String, Method>> ACCESSORS = new ConcurrentHashMap<>();

    private AggregateScope() {}

    /**
     * 校验聚合当前状态是否落在写范围内。
     *
     * @param aggregate 聚合实例
     * @param scope 写范围
     * @param schema 对象 schema
     * @throws ContractException scope 不匹配（403）
     * @throws IllegalStateException 聚合缺少谓词引用字段的访问器（部署错误）
     */
    static void requireWithin(Object aggregate, ScopePredicate scope, ObjectSchema schema) {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(schema, "schema");
        if (scope instanceof ScopePredicate.All) {
            return;
        }
        Set<String> referenced = new LinkedHashSet<>();
        collectFields(scope, referenced);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : referenced) {
            values.put(field, read(aggregate, field));
        }
        if (!ScopePredicates.matches(values, scope, schema)) {
            throw new ContractException(
                    ContractErrorCode.FORBIDDEN,
                    "object is outside write scope: " + schema.objectType());
        }
    }

    private static Object read(Object aggregate, String field) {
        Method accessor = accessor(aggregate.getClass(), field);
        try {
            return accessor.invoke(aggregate);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException(
                    "scope accessor " + accessor + " failed", error.getCause());
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(
                    "scope accessor " + accessor + " is not accessible", error);
        }
    }

    private static Method accessor(Class<?> aggregateType, String field) {
        Map<String, Method> cache =
                ACCESSORS.computeIfAbsent(aggregateType, ignored -> new ConcurrentHashMap<>());
        Method cached = cache.get(field);
        if (cached != null) {
            return cached;
        }
        Method located = locate(aggregateType, field);
        if (located == null) {
            throw new IllegalStateException(
                    "aggregate "
                            + aggregateType.getName()
                            + " has no accessor for scope field "
                            + field);
        }
        located.trySetAccessible();
        cache.putIfAbsent(field, located);
        return located;
    }

    private static Method locate(Class<?> aggregateType, String field) {
        String camel = camelCase(field);
        List<String> candidates =
                List.of(camel, "get" + capitalize(camel), "is" + capitalize(camel));
        for (String candidate : candidates) {
            try {
                Method method = aggregateType.getMethod(candidate);
                if (method.getParameterCount() == 0) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // fall through to declared methods
            }
        }
        for (Class<?> current = aggregateType;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && candidates.contains(method.getName())) {
                    return method;
                }
            }
        }
        return null;
    }

    private static void collectFields(ScopePredicate predicate, Set<String> fields) {
        switch (predicate) {
            case ScopePredicate.All ignored -> {}
            case ScopePredicate.None ignored -> {}
            case ScopePredicate.Eq eq -> fields.add(eq.field());
            case ScopePredicate.In in -> fields.add(in.field());
            case ScopePredicate.NotNull notNull -> fields.add(notNull.field());
            case ScopePredicate.And and -> and.parts().forEach(part -> collectFields(part, fields));
            case ScopePredicate.Or or -> or.parts().forEach(part -> collectFields(part, fields));
            case ScopePredicate.Not not -> collectFields(not.part(), fields);
        }
    }

    private static String camelCase(String field) {
        StringBuilder out = new StringBuilder(field.length());
        boolean upper = false;
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
