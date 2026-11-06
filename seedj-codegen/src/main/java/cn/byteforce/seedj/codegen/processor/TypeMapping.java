package cn.byteforce.seedj.codegen.processor;

import cn.byteforce.seedj.domain.FieldType;

/**
 * Java 类型到 {@link FieldType} 的映射。
 *
 * <p>只接受可无歧义映射的类型；其余类型在编译期报错，而不是静默落到 {@code TEXT}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class TypeMapping {

    private TypeMapping() {
    }

    /**
     * 推导字段类型。
     *
     * @param typeName 类型全名或基本类型名
     * @param enumType 是否为枚举
     * @return 字段类型；无法映射时为 {@code null}
     */
    static FieldType infer(String typeName, boolean enumType) {
        if (enumType) {
            return FieldType.TEXT;
        }
        return switch (typeName) {
            case "java.lang.String", "char", "java.lang.Character" -> FieldType.TEXT;
            case "int", "java.lang.Integer" -> FieldType.INTEGER;
            case "long", "java.lang.Long" -> FieldType.LONG;
            case "java.math.BigDecimal", "double", "java.lang.Double", "float",
                    "java.lang.Float" -> FieldType.DECIMAL;
            case "boolean", "java.lang.Boolean" -> FieldType.BOOLEAN;
            case "java.time.LocalDate" -> FieldType.DATE;
            case "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime" ->
                    FieldType.TIMESTAMP;
            case "java.util.UUID" -> FieldType.UUID;
            default -> null;
        };
    }
}
