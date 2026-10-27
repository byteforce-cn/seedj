package cn.byteforce.seedj.starter.eis.domain.internal;

import cn.byteforce.seedj.starter.eis.domain.DomainEvent;
import cn.byteforce.seedj.starter.eis.domain.EventApplier;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code apply(X)} 分派表的构建与查询（库内部，不属于业务 API）。
 *
 * <p>运行时分派与启动期校验共用同一张表，避免「启动校验说没问题、运行期却分派不到」
 * 这类最難排查的错位。分派表来自 {@code getDeclaredMethods()} 而不是
 * {@code getMethods()}：{@code apply} 应声明为 {@code protected}，
 * 让「从外部直接改状态」写不出来。</p>
 *
 * <p>每个聚合类型只扫描一次，之后所有实例共享缓存。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EventDispatch {

    /**
     * 聚合类型 → （事件类型 → apply 方法）。
     */
    private static final Map<Class<?>, Map<Class<?>, Method>> CACHE = new ConcurrentHashMap<>();

    /**
     * 聚合类型 → 事件族（{@code EventApplier<X>} 的 X）。
     */
    private static final Map<Class<?>, Optional<Class<? extends DomainEvent>>> FAMILIES =
            new ConcurrentHashMap<>();

    private EventDispatch() {
    }

    /**
     * 返回聚合的分派表（不可变）。
     *
     * @param aggregateType 聚合类型
     * @return 事件类型到 apply 方法的映射
     */
    public static Map<Class<?>, Method> table(Class<?> aggregateType) {
        return CACHE.computeIfAbsent(aggregateType, EventDispatch::build);
    }

    /**
     * 查找事件对应的 apply 方法：先精确匹配，再取最具体的父类型匹配。
     *
     * @param aggregateType 聚合类型
     * @param eventType     事件类型
     * @return apply 方法；不存在时为空
     */
    public static Optional<Method> methodFor(Class<?> aggregateType, Class<?> eventType) {
        Map<Class<?>, Method> table = table(aggregateType);
        Method exact = table.get(eventType);
        if (exact != null) {
            return Optional.of(exact);
        }
        return Optional.ofNullable(findAssignableHandler(table, eventType));
    }

    /**
     * 返回聚合已声明处理的事件类型（含父类型）。
     *
     * @param aggregateType 聚合类型
     * @return 事件类型集合
     */
    public static Set<Class<?>> handledEventTypes(Class<?> aggregateType) {
        return new LinkedHashSet<>(table(aggregateType).keySet());
    }

    /**
     * 解析 {@code implements EventApplier<X>} 里的 {@code X}。
     *
     * <p>支持直接实现、经父接口间接实现、从父类继承；泛型参数是类型变量时无法解析，
     * 返回空。</p>
     *
     * @param aggregateType 聚合类型
     * @return 事件族；无法解析时为空
     */
    public static Optional<Class<? extends DomainEvent>> eventFamily(Class<?> aggregateType) {
        return FAMILIES.computeIfAbsent(aggregateType, EventDispatch::resolveFamily);
    }

    /**
     * 返回所有「看起来像 apply」的方法：名字是 apply、只有一个参数、非 static/bridge/synthetic。
     *
     * <p>启动期用它检查参数类型是否实现 {@link DomainEvent}——这类写法永远分派不到。</p>
     *
     * @param aggregateType 聚合类型
     * @return apply 候选方法
     */
    public static List<Method> applyMethods(Class<?> aggregateType) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> type = aggregateType; type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (isApplyCandidate(method)) {
                    methods.add(method);
                }
            }
        }
        return List.copyOf(methods);
    }

    /**
     * 返回 sealed 事件族中没有对应 apply 的子类型。
     *
     * <p>覆盖可以是精确的 {@code apply(OrderCreated)}，也可以是父类型
     * {@code apply(OrderEvent)}（穷尽 switch 写法）。</p>
     *
     * @param aggregateType   聚合类型
     * @param sealedEventType sealed 事件父类型
     * @return 缺失的子类型（空表示穷尽）
     * @throws IllegalArgumentException 传入类型不是 sealed
     */
    public static List<Class<?>> missingHandlers(Class<?> aggregateType, Class<?> sealedEventType) {
        if (!sealedEventType.isSealed()) {
            throw new IllegalArgumentException(
                    sealedEventType.getName() + " is not a sealed interface/class");
        }
        Set<Class<?>> declared = handledEventTypes(aggregateType);
        List<Class<?>> missing = new ArrayList<>();
        for (Class<?> permitted : sealedEventType.getPermittedSubclasses()) {
            boolean covered = declared.stream().anyMatch(handler -> handler.isAssignableFrom(permitted));
            if (!covered) {
                missing.add(permitted);
            }
        }
        return missing;
    }

    /**
     * 判断方法是否可能是事件处理方法。
     *
     * @param method 待判断方法
     * @return 是候选时为 {@code true}
     */
    static boolean isApplyCandidate(Method method) {
        return method.getName().equals("apply")
                && method.getParameterCount() == 1
                && !Modifier.isStatic(method.getModifiers())
                && !method.isBridge()
                && !method.isSynthetic();
    }

    private static Map<Class<?>, Method> build(Class<?> aggregateType) {
        Map<Class<?>, Method> table = new LinkedHashMap<>();
        // 子类优先：同一事件类型在子类被覆盖时，先到的声明胜出。
        for (Class<?> type = aggregateType; type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!isApplyCandidate(method) || Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                Class<?> eventType = method.getParameterTypes()[0];
                if (!DomainEvent.class.isAssignableFrom(eventType) || table.containsKey(eventType)) {
                    continue;
                }
                if (!method.trySetAccessible()) {
                    throw new IllegalStateException("""
                            Cannot access %s#apply(%s) for event dispatch.
                            Fix: declare it public or protected, e.g.
                                protected void apply(%s event) { ... }
                            """.formatted(
                            type.getSimpleName(),
                            eventType.getSimpleName(),
                            eventType.getSimpleName()));
                }
                table.put(eventType, method);
            }
        }
        return Collections.unmodifiableMap(table);
    }

    /**
     * 允许 {@code apply(SomeSealedParent)} 处理所有子类型；取最具体的匹配项。
     */
    private static Method findAssignableHandler(Map<Class<?>, Method> table, Class<?> eventType) {
        Class<?> best = null;
        for (Class<?> candidate : table.keySet()) {
            if (!candidate.isAssignableFrom(eventType)) {
                continue;
            }
            if (best == null || best.isAssignableFrom(candidate)) {
                best = candidate;
            }
        }
        return best == null ? null : table.get(best);
    }

    private static Optional<Class<? extends DomainEvent>> resolveFamily(Class<?> aggregateType) {
        for (Class<?> type = aggregateType; type != null && type != Object.class;
             type = type.getSuperclass()) {
            Class<? extends DomainEvent> family = familyIn(type.getGenericInterfaces());
            if (family != null) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }

    private static Class<? extends DomainEvent> familyIn(Type[] interfaces) {
        for (Type candidate : interfaces) {
            Class<?> rawType = rawClass(candidate);
            if (rawType == null) {
                continue;
            }
            if (rawType == EventApplier.class) {
                Class<? extends DomainEvent> family = familyOf(candidate);
                if (family != null) {
                    return family;
                }
                continue;
            }
            Class<? extends DomainEvent> nested = familyIn(rawType.getGenericInterfaces());
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static Class<? extends DomainEvent> familyOf(Type applier) {
        if (!(applier instanceof ParameterizedType parameterized)) {
            return null;
        }
        Type argument = parameterized.getActualTypeArguments()[0];
        if (argument instanceof Class<?> type && DomainEvent.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            Class<? extends DomainEvent> family = (Class<? extends DomainEvent>) type;
            return family;
        }
        return null;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }
}
