package cn.byteforce.seedj.domain.event;

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
 * {@code apply(X)} 分派表的构建与查询。
 *
 * <p>纯 JDK 实现，不依赖任何框架，也不依赖聚合实例： 运行时由 {@link AbstractAggregateRoot} 使用，启动期由装配层用同一个类做穷尽性校验，
 * 保证「运行时分派」和「启动期校验」看到的是同一张表。
 *
 * <p>分派表来自 {@code getDeclaredMethods()}（沿继承链向上收集）而不是 {@code getMethods()}：{@code apply}
 * 应当只由框架和聚合自己调用，声明成 {@code protected} 才是对的。为了能调用非 public 方法，收集时统一 {@code
 * setAccessible(true)}；不可访问时直接失败，而不是等到运行期某个事件触发时才炸。
 */
public final class EventDispatch {

    /** 每个聚合类型只扫描一次方法，之后所有实例共享。 */
    private static final Map<Class<?>, Map<Class<?>, Method>> CACHE = new ConcurrentHashMap<>();

    /** 每个聚合类型的事件族只解析一次。 */
    private static final Map<Class<?>, Optional<Class<? extends DomainEvent>>> FAMILIES =
            new ConcurrentHashMap<>();

    private EventDispatch() {}

    /** 聚合类型的分派表：事件类型 → {@code apply(X)} 方法。 */
    public static Map<Class<?>, Method> table(Class<?> aggregateType) {
        return CACHE.computeIfAbsent(aggregateType, EventDispatch::build);
    }

    /** 查找某个事件对应的 apply 方法。先精确匹配，再取最具体的父类型匹配。 */
    public static Optional<Method> methodFor(Class<?> aggregateType, Class<?> eventType) {
        Map<Class<?>, Method> table = table(aggregateType);
        Method exact = table.get(eventType);
        if (exact != null) {
            return Optional.of(exact);
        }
        return Optional.ofNullable(findAssignableHandler(table, eventType));
    }

    /** 聚合已经声明处理的事件类型（含父类型）。 */
    public static Set<Class<?>> handledEventTypes(Class<?> aggregateType) {
        return new LinkedHashSet<>(table(aggregateType).keySet());
    }

    /**
     * {@code implements EventApplier<X>} 里的 {@code X}。
     *
     * <p>支持直接实现、经由父接口间接实现、以及从父类继承。泛型参数是类型变量 （例如 {@code class Base<E> implements
     * EventApplier<E>}）时解析不出具体类型，返回空。
     */
    public static Optional<Class<? extends DomainEvent>> eventFamily(Class<?> aggregateType) {
        return FAMILIES.computeIfAbsent(aggregateType, EventDispatch::resolveFamily);
    }

    /**
     * 所有「看起来像 apply」的方法：名字是 apply、只有一个参数、非 static/bridge/synthetic。
     *
     * <p>启动期用它检查参数类型是不是 {@link DomainEvent}——这类写法分派永远命中不了。
     */
    public static List<Method> applyMethods(Class<?> aggregateType) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> type = aggregateType;
                type != null && type != Object.class;
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
     * sealed 事件类型中没有任何 apply 方法覆盖的子类型。
     *
     * <p>覆盖可以是精确的 {@code apply(OrderCreated)}，也可以是父类型 {@code apply(OrderEvent)}（switch 穷尽分派写法）。
     */
    public static List<Class<?>> missingHandlers(Class<?> aggregateType, Class<?> sealedEventType) {
        if (!sealedEventType.isSealed()) {
            throw new IllegalArgumentException(
                    sealedEventType.getName() + " is not a sealed interface/class");
        }
        Set<Class<?>> declared = handledEventTypes(aggregateType);
        List<Class<?>> missing = new ArrayList<>();
        for (Class<?> permitted : sealedEventType.getPermittedSubclasses()) {
            boolean covered =
                    declared.stream().anyMatch(handler -> handler.isAssignableFrom(permitted));
            if (!covered) {
                missing.add(permitted);
            }
        }
        return missing;
    }

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
        for (Class<?> type = aggregateType;
                type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!isApplyCandidate(method) || Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                Class<?> eventType = method.getParameterTypes()[0];
                if (!DomainEvent.class.isAssignableFrom(eventType)
                        || table.containsKey(eventType)) {
                    continue;
                }
                if (!method.trySetAccessible()) {
                    throw new IllegalStateException(
                            """
                            Cannot access %s#apply(%s) for event dispatch.
                            Fix: declare it public or protected, e.g.
                                protected void apply(%s event) { ... }
                            """
                                    .formatted(
                                            type.getSimpleName(),
                                            eventType.getSimpleName(),
                                            eventType.getSimpleName()));
                }
                table.put(eventType, method);
            }
        }
        return Collections.unmodifiableMap(table);
    }

    /** 允许 {@code apply(SomeSealedParent)} 处理所有子类型；取最具体的匹配项。 */
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
        for (Class<?> type = aggregateType;
                type != null && type != Object.class;
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
