package cn.byteforce.seedj.starter.read;

import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.TemporalRow;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行 → record 视图映射：默认 snake_case ↔ camelCase，元结构从 {@link ObjectVersion} 取。
 *
 * <p>组件没有对应字段时为 {@code null}；primitive 组件缺值直接失败，不静默给 0。
 * 不支持接口投影，避免第二套映射语义。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ViewMapper {

    private final Map<Class<?>, Constructor<?>> constructors = new ConcurrentHashMap<>();

    /**
     * 映射一行。
     *
     * @param row      渲染后的行
     * @param viewType record 视图类型
     * @return 视图实例
     */
    public Object map(TemporalRow row, Class<?> viewType) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(viewType, "viewType");
        Constructor<?> constructor = constructors.computeIfAbsent(viewType,
                ViewMapper::constructor);
        RecordComponent[] components = constructor.getDeclaringClass().getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            args[i] = value(row, component);
        }
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot map " + row.objectType() + " to "
                    + viewType.getName(), error);
        }
    }

    private static Constructor<?> constructor(Class<?> viewType) {
        if (!viewType.isRecord()) {
            throw new IllegalArgumentException("view type must be a record: "
                    + viewType.getName());
        }
        try {
            return viewType.getDeclaredConstructor(java.util.Arrays.stream(
                            viewType.getRecordComponents())
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new));
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException("record canonical constructor is missing", error);
        }
    }

    private static Object value(TemporalRow row, RecordComponent component) {
        String name = component.getName();
        ObjectVersion version = row.version();
        Object value = switch (name) {
            case "id" -> version.id();
            case "version" -> version.version();
            case "validFrom" -> version.validFrom();
            case "validTo" -> version.validTo();
            case "recordedAt" -> version.recordedAt();
            case "supersededAt" -> version.supersededAt();
            case "createdBy" -> version.createdBy();
            case "createdAt" -> version.createdAt();
            case "updatedBy" -> version.updatedBy();
            case "updatedAt" -> version.updatedAt();
            default -> row.fields().containsKey(name)
                    ? row.fields().get(name)
                    : row.fields().get(snakeCase(name));
        };
        return convert(value, component.getType(), name);
    }

    private static Object convert(Object value, Class<?> type, String field) {
        if (value == null) {
            if (type.isPrimitive()) {
                throw new IllegalArgumentException(
                        "primitive view component " + field + " has no value");
            }
            return null;
        }
        if (type.isInstance(value)) {
            return value;
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        if (type == Integer.class || type == int.class) {
            return value instanceof Number number ? number.intValue()
                    : Integer.valueOf(value.toString());
        }
        if (type == Long.class || type == long.class) {
            return value instanceof Number number ? number.longValue()
                    : Long.valueOf(value.toString());
        }
        if (type == BigDecimal.class) {
            return value instanceof BigDecimal decimal ? decimal
                    : new BigDecimal(value.toString());
        }
        if (type == Boolean.class || type == boolean.class) {
            return value instanceof Boolean bool ? bool : Boolean.valueOf(value.toString());
        }
        if (type == LocalDate.class) {
            return value instanceof LocalDate date ? date : LocalDate.parse(value.toString());
        }
        if (type == Instant.class) {
            return value instanceof Instant instant ? instant : Instant.parse(value.toString());
        }
        if (type == UUID.class) {
            return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
        }
        throw new IllegalArgumentException("cannot convert field " + field + " from "
                + value.getClass().getName() + " to " + type.getName());
    }

    private static String snakeCase(String camel) {
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
