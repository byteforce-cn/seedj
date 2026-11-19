/**
 * 策略版本钉死（T1-local）：求值端口、head 端口与配置驱动的默认实现。
 *
 * <p><b>职责</b>：{@link cn.byteforce.seedj.starter.ai.policy.PolicyEvaluator} 把一条决策求值成 {@code
 * PolicyRef}（引擎、包、版本、纪元、结论），由决策面写进 intent 与 DecisionToken；{@link
 * cn.byteforce.seedj.starter.ai.policy.PolicyHeadSource} 回答「当前 head 是哪一版」，由守卫对不可逆动作校验新鲜度。
 * 两个端口都由宿主可替换（{@code @ConditionalOnMissingBean}），默认实现只读 {@code seedj.ai.policy.*}。
 *
 * <p><b>边界</b>：本包不做规则求值、不读 coord、不写账本、不校验 token。coord 的 {@code Evaluate} 返回实际求值版本
 * （上游 U8）之后，适配器只需实现这两个端口，token 与账本字段不变。
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.starter.ai.policy;
