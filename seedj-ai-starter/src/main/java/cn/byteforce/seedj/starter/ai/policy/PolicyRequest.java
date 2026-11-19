package cn.byteforce.seedj.starter.ai.policy;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.DecisionKind;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次策略求值的输入：只给判定所需的事实，<b>不含命令载荷</b>。
 *
 * <p>策略引擎要回答的是「这一步该不该做」，而不是「载荷长什么样」；载荷已由 {@code args_hash} 概括，原文留在调用方与
 * 账本之外。这样宿主把求值委托给外部引擎时，不必把业务字段送出去。
 *
 * <p>{@code argsHash} 与账本、守卫用的是同一个值：策略判定与执行绑定的是同一次参数形态。{@code actionClass} 只在 {@code EXECUTE_TOOL}
 * 时非空——与 {@code Proposal}/{@code DecisionIntent} 的约束一致。
 *
 * @param tenantId 租户标识（决策面配置钉死）
 * @param decisionId 本次决策标识（同时是命令幂等键）
 * @param taskContractId 任务契约标识
 * @param conversationId 会话标识
 * @param step 会话内步序，从 1 开始
 * @param kind 步骤类型
 * @param actionClass 动作分类；非 {@code EXECUTE_TOOL} 时为 {@code null}
 * @param commandType 命令类型
 * @param argsHash 参数指纹
 * @param modelName 模型名（只用于策略分层，不参与授权）
 * @author ByteForce
 * @since 1.0.0
 */
public record PolicyRequest(
        UUID tenantId,
        UUID decisionId,
        UUID taskContractId,
        UUID conversationId,
        int step,
        DecisionKind kind,
        ActionClass actionClass,
        String commandType,
        String argsHash,
        String modelName) {

    /**
     * 校验必填项与步序。
     *
     * @throws NullPointerException {@code tenantId}/{@code decisionId}/{@code taskContractId}/
     *     {@code conversationId}/{@code kind}/{@code commandType}/{@code argsHash} 为 {@code null}
     * @throws IllegalArgumentException {@code commandType} 或 {@code argsHash} 为空白
     */
    public PolicyRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(taskContractId, "taskContractId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(kind, "kind");
        // step 原样透传：决策面接受什么步序，策略看到的就应当是什么，不在这里另立一套校验。
        commandType = requiredText(commandType, "commandType");
        argsHash = requiredText(argsHash, "argsHash");
    }

    private static String requiredText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
