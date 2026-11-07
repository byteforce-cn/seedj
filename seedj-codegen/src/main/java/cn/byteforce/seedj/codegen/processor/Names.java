package cn.byteforce.seedj.codegen.processor;

import java.util.Locale;

/**
 * 命名转换：record 组件的 camelCase → schema 的 snake_case。
 *
 * <p>规则与 {@code ObjectModules} 的载荷字段推导保持一致：每个大写字母前插入下划线并转小写
 * （{@code orgUnitId → org_unit_id}）。保持一致比“更聪明的缩写处理”重要——命令载荷、
 * schema 与 HTTP 字段必须落在同一个名字上。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class Names {

    private Names() {
    }

    static String snakeCase(String camel) {
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
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * schema 的 snake_case → Java 组件 camelCase，用于生成读视图。
     *
     * @param snake snake_case 字段名
     * @return camelCase 组件名
     */
    static String camelCase(String snake) {
        StringBuilder out = new StringBuilder(snake.length());
        boolean upper = false;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }
}
