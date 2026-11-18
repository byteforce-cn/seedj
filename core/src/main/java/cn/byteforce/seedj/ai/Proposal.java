package cn.byteforce.seedj.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * agent 提议的下一步：执行工具前交给守卫/审批链的候选动作。
 *
 * <p>与 {@link DecisionIntent} 的区别是“还没有落账”：提议可以只读、可以追问、可以被拒绝，因此不要求
 * {@code decisionId} 与指纹。执行工具的提议必须带齐动作分类、命令与载荷——守卫无法在缺字段的提议上做判断。
 *
 * @param kind 步骤类型
 * @param actionClass 动作分类；{@code kind == EXECUTE_TOOL} 时必填
 * @param command 命令引用；{@code kind == EXECUTE_TOOL} 时必填
 * @param payload 命令载荷；{@code kind == EXECUTE_TOOL} 时必填；允许 null value，键序保留
 * @param rationale 提议理由；{@code null} 表示未给出
 * @param evidence 支撑证据；{@code null} 视为空列表
 * @author ByteForce
 * @since 1.0.0
 */
public record Proposal(
        DecisionKind kind,
        ActionClass actionClass,
        CommandRef command,
        Map<String, Object> payload,
        String rationale,
        List<Evidence> evidence) {

    /**
     * 校验步骤类型与“执行工具”所需字段的对应关系。
     *
     * @throws NullPointerException {@code kind} 为 {@code null}，或执行工具时缺少动作分类/命令/载荷
     * @throws IllegalArgumentException 非执行工具的提议带了动作分类/命令/载荷
     */
    public Proposal {
        kind = Objects.requireNonNull(kind, "kind");
        if (kind == DecisionKind.EXECUTE_TOOL) {
            Objects.requireNonNull(actionClass, "actionClass");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(payload, "payload");
            payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        } else if (actionClass != null || command != null || payload != null) {
            throw new IllegalArgumentException(
                    "actionClass/command/payload are only allowed for EXECUTE_TOOL");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
