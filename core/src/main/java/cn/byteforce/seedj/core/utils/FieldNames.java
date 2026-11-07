package cn.byteforce.seedj.core.utils;

/**
 * 业务字段命名转换：命令 record、schema 与视图共用同一份规则。
 *
 * <p>只在 core 暴露一个实现，避免 starter 与 module 各自复制一份
 * {@code snakeCase}，导致同一个 Java 组件在不同层映射出不同字段名。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class FieldNames {

    private FieldNames() {
    }

    /**
     * camelCase 转 snake_case；已经是 snake_case 的名称保持不变。
     *
     * <pre>{@code
     * FieldNames.snakeCase("orgUnitId") // "org_unit_id"
     * FieldNames.snakeCase("employee_no") // "employee_no"
     * }</pre>
     *
     * @param camel Java 字段名
     * @return 线格式字段名
     */
    public static String snakeCase(String camel) {
        if (camel == null) {
            throw new IllegalArgumentException("field name must not be null");
        }
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
