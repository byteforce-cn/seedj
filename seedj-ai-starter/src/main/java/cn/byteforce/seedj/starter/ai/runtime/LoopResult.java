package cn.byteforce.seedj.starter.ai.runtime;

import java.util.List;
import java.util.Objects;

/**
 * 一次有界循环的结局。
 *
 * <p>{@code detail} 只在两种结局里有值：拒绝时是 {@code denyRule}，工具失败时是工具返回体（截断到 {@value #MAX_DETAIL_CHARS}
 * 字符）。本类不主动写入 token：{@code detail} 原样保留工具返回体（外部文本）， {@code observations} 由 {@code BoundedLoop}
 * 生成且只含 step / kind / type / ok / commandId。
 *
 * @param outcome 结局类型
 * @param steps 实际走完的步数
 * @param detail 停止原因的可读说明；正常收尾与到达上限时为空
 * @param observations 各步观测文本，按步序排列
 * @author ByteForce
 * @since 1.0.0
 */
public record LoopResult(Outcome outcome, int steps, String detail, List<String> observations) {

    /** 工具失败时 {@code detail} 保留的最大字符数：够定位问题，又不把大响应塞进结局。 */
    public static final int MAX_DETAIL_CHARS = 512;

    /** 循环的结局类型。 */
    public enum Outcome {

        /** 模型给出 {@code finish}，任务正常收尾。 */
        FINISHED,

        /** 决策面拒绝某一步：契约不允许或契约不存在。 */
        DENIED,

        /** 工具调用失败，循环就地停止。 */
        TOOL_FAILED,

        /** 走满步数上限仍未收尾。 */
        MAX_STEPS_REACHED,

        /** 某一步在等待人工审批：没有执行，也没有失败。 */
        PENDING_APPROVAL
    }

    /**
     * 校验结局并归一化观测列表。
     *
     * @throws NullPointerException {@code outcome} 为 {@code null}
     */
    public LoopResult {
        Objects.requireNonNull(outcome, "outcome");
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    /**
     * 正常收尾。
     *
     * @param steps 实际步数
     * @param observations 各步观测
     * @return 结局
     */
    public static LoopResult finished(int steps, List<String> observations) {
        return new LoopResult(Outcome.FINISHED, steps, null, observations);
    }

    /**
     * 被决策面拒绝。
     *
     * @param steps 实际步数
     * @param denyRule 拒绝原因码
     * @param observations 各步观测
     * @return 结局
     */
    public static LoopResult denied(int steps, String denyRule, List<String> observations) {
        return new LoopResult(Outcome.DENIED, steps, denyRule, observations);
    }

    /**
     * 工具失败。
     *
     * @param steps 实际步数
     * @param body 工具返回体，截断到 {@value #MAX_DETAIL_CHARS} 字符
     * @param observations 各步观测
     * @return 结局
     */
    public static LoopResult toolFailed(int steps, String body, List<String> observations) {
        return new LoopResult(Outcome.TOOL_FAILED, steps, truncate(body), observations);
    }

    /**
     * 走满步数上限。
     *
     * @param steps 实际步数
     * @param observations 各步观测
     * @return 结局
     */
    public static LoopResult maxStepsReached(int steps, List<String> observations) {
        return new LoopResult(Outcome.MAX_STEPS_REACHED, steps, null, observations);
    }

    /**
     * 停在待审批：{@code detail} 是 decision_id 字符串。
     *
     * @param steps 实际步数
     * @param decisionId 决策标识
     * @param observations 各步观测
     * @return 结局
     */
    public static LoopResult pendingApproval(
            int steps, java.util.UUID decisionId, List<String> observations) {
        java.util.Objects.requireNonNull(decisionId, "decisionId");
        return new LoopResult(
                Outcome.PENDING_APPROVAL, steps, decisionId.toString(), observations);
    }

    private static String truncate(String body) {
        if (body == null || body.length() <= MAX_DETAIL_CHARS) {
            return body;
        }
        return body.substring(0, MAX_DETAIL_CHARS);
    }
}
