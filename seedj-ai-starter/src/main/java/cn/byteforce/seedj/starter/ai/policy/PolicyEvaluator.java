package cn.byteforce.seedj.starter.ai.policy;

import cn.byteforce.seedj.ai.PolicyRef;

/**
 * 策略求值端口：把「这一步」交给策略引擎，拿回一个策略引用（含结论）。
 *
 * <p><b>返回的 {@link PolicyRef#verdict()} 就是结论</b>：只有 {@code allow} 放行，其它取值（含 {@code deny}、
 * 未知值、{@code not_evaluated}）一律由决策面拒绝——策略引擎的未知结论不能当放行。
 *
 * <p>实现方只做判定，不签发 token、不写账本：策略引用的落账与绑定由 {@code DecisionService} 负责。求值失败应当抛异常， 决策面会让请求失败且不落
 * intent——求值失败不是决策，不能伪造一条意图。
 *
 * <p>默认实现是 {@link ConfiguredPolicyEvaluator}（钉配置版本、结论恒为 {@code allow}）；接 coord 的适配器等上游 {@code
 * Evaluate} 返回实际求值版本后替换本端口即可。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface PolicyEvaluator {

    /**
     * 对一步决策求值。
     *
     * @param request 求值输入
     * @return 策略引用；{@code verdict} 为 {@code allow} 之外的任何值都意味着拒绝
     */
    PolicyRef evaluate(PolicyRequest request);

    /**
     * 不评估：返回 {@link PolicyRef#none()}，与 T0 行为一致。
     *
     * @return 常量实现
     */
    static PolicyEvaluator none() {
        return request -> PolicyRef.none();
    }
}
