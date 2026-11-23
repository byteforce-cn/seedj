package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.seedj.workflow.spi.ParticipantResolutionException;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * seedj 参与者表达式：{@code ${x}} 的解析与 coord jq 改写。
 *
 * <p>coord 的 {@code approve} 节点用 jq 形状 {@code ${ $context.x }}；seedj 的表达式是裸变量名 {@code
 * ${x}}。两者只在编译期改写，运行期仍由 coord 求值（数组结果展开为多个席位）。
 */
final class ParticipantExpressions {

    private ParticipantExpressions() {}

    /** 编译期：{@code ${x}} → {@code ${ $context.x }}；已是 jq 形状的原样保留。 */
    static String toJq(String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (!trimmed.startsWith("${") || !trimmed.endsWith("}")) {
            return expression;
        }
        String body = trimmed.substring(2, trimmed.length() - 1).trim();
        if (body.startsWith("$") || body.startsWith(".")) {
            return trimmed;
        }
        return "${ $context." + body + " }";
    }

    /** 运行前解析：{@code ${key}} 从 variables 取值，支持逗号分隔的多个参与者。 */
    static List<ParticipantSpec> resolve(
            String expression, ParticipantType type, Map<String, String> variables) {
        if (expression == null || expression.isBlank()) {
            throw new ParticipantResolutionException("participant expression is blank");
        }
        String trimmed = expression.trim();
        String resolved = trimmed;
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String key = trimmed.substring(2, trimmed.length() - 1).trim();
            if (key.isEmpty()) {
                throw new ParticipantResolutionException("empty participant expression");
            }
            String value = variables.get(key);
            if (value == null || value.isBlank()) {
                throw new ParticipantResolutionException(
                        "participant expression '${" + key + "}' has no value in variables");
            }
            resolved = value;
        }
        List<ParticipantSpec> specs = new ArrayList<>();
        for (String part : resolved.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                specs.add(new ParticipantSpec(type, value, Map.of()));
            }
        }
        if (specs.isEmpty()) {
            throw new ParticipantResolutionException(
                    "participant expression '" + expression + "' resolved to no participants");
        }
        return specs;
    }
}
