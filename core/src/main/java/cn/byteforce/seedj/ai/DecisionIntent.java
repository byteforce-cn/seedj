package cn.byteforce.seedj.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 决策意图：agent 决定“下一步做什么”的完整声明，账本的第一条记录。
 *
 * <p>意图先落账、后执行：只有账本写成功才可能拿到决策 token，因此任何执行都能被回溯到一条意图。载荷只保留脱敏后的可展示形式 （{@code args}）与指纹（{@code
 * argsHash}），原文不入账。
 *
 * <p>动作分类只在执行工具时存在：{@code kind == EXECUTE_TOOL} 必须给出 {@code actionClass}，其它步骤必须为 {@code
 * null}——两者错配说明构造方在混用两套语义。审批快照只在需要审批的动作分类上出现。
 *
 * @param decisionId 决策标识（一次 agent 决策，跨多条记录不变）
 * @param tenantId 租户
 * @param taskContractId 任务契约标识
 * @param conversationId 会话标识
 * @param step 决策步序号
 * @param kind 步骤类型
 * @param actionClass 动作分类；仅 {@code kind == EXECUTE_TOOL} 时非空
 * @param command 命令引用
 * @param argsHash 参数指纹（{@code sha256:} 前缀）
 * @param idemFingerprint 幂等指纹（64 位小写十六进制）
 * @param args 脱敏后的可展示参数；{@code null} 入参视为空映射。入参会被规范化为 JSON 树（按存储读回的方式过一遍 JSON 文本：{@code
 *     List<Integer>}、{@code Double}、嵌套 {@code Map}），保留键序与 null 值；非 JSON 可序列化的对象直接失败
 * @param rationale 决策理由
 * @param evidence 支撑证据；{@code null} 视为空列表
 * @param model 决策所用模型
 * @param policy 策略判定引用；{@code null} 表示未挂策略层
 * @param identity 发起主体
 * @param approval 审批快照；仅需要审批的动作分类可非空
 * @author ByteForce
 * @since 1.0.0
 */
public record DecisionIntent(
        UUID decisionId,
        UUID tenantId,
        UUID taskContractId,
        UUID conversationId,
        int step,
        DecisionKind kind,
        ActionClass actionClass,
        CommandRef command,
        String argsHash,
        String idemFingerprint,
        Map<String, Object> args,
        String rationale,
        List<Evidence> evidence,
        ModelRef model,
        PolicyRef policy,
        AgentIdentity identity,
        ApprovalSnapshot approval) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> ARGS = new TypeReference<>() {};

    /**
     * 校验必填项、把 {@code args} 规范化为 JSON 树，并校验“步骤类型 ↔ 动作分类/审批”的对应关系。
     *
     * <p>{@code args} 规范化：按存储看到的方式过一遍 JSON 文本（写一次再读回）。账本哈希覆盖记录体， 内存实现看的是这里的对象图、PostgreSQL 实现看的是
     * jsonb 读回的文本，两边的装箱类型（{@code Float}/{@code Long} 等）不同会让“写入时哈希”与“校验时哈希”分叉；过了 JSON 文本后两边看到同一棵树：
     * 浮点数落成 {@code Double}、整数落成 {@code Integer}/{@code Long}，金额请以字符串传入。
     *
     * @throws NullPointerException 必填项为 {@code null}
     * @throws IllegalArgumentException 必填文本为空白、{@code args} 含非 JSON 可序列化对象、
     *     步骤类型与动作分类错配，或不该有审批的步骤带了审批快照
     */
    public DecisionIntent {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(taskContractId, "taskContractId");
        Objects.requireNonNull(conversationId, "conversationId");
        kind = Objects.requireNonNull(kind, "kind");
        command = Objects.requireNonNull(command, "command");
        argsHash = requiredText(argsHash, "argsHash");
        idemFingerprint = requiredText(idemFingerprint, "idemFingerprint");
        rationale = requiredText(rationale, "rationale");
        model = Objects.requireNonNull(model, "model");
        identity = Objects.requireNonNull(identity, "identity");
        if (kind == DecisionKind.EXECUTE_TOOL) {
            Objects.requireNonNull(actionClass, "actionClass");
        } else if (actionClass != null) {
            throw new IllegalArgumentException("actionClass is only allowed for EXECUTE_TOOL");
        }
        if (approval != null && (actionClass == null || !actionClass.requiresApproval())) {
            throw new IllegalArgumentException(
                    "approval is only allowed for approval-requiring actions");
        }
        args = normalize(args);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** 把入参换成存储读回后的值：过一遍 JSON 文本、键序保留、null 值保留。 */
    private static Map<String, Object> normalize(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized;
        try {
            normalized = JSON.readValue(JSON.writeValueAsString(args), ARGS);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalArgumentException("args must be JSON-serializable", e);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    /**
     * 该意图所在账本分区。
     *
     * @return 分区名
     */
    public String partition() {
        return DecisionRecord.partitionOf(tenantId);
    }

    private static String requiredText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
