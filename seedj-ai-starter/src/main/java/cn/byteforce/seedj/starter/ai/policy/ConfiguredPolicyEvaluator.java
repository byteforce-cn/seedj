package cn.byteforce.seedj.starter.ai.policy;

import cn.byteforce.seedj.ai.PolicyRef;
import java.util.List;
import java.util.Objects;

/**
 * 配置驱动的策略求值器：T1-local 不跑规则，只把配置里的版本钉进每条决策。
 *
 * <p>结论恒为 {@code allow}——本地没有规则可判，这个 {@code allow} 说的是「本地不拦」，不是「策略允许」。真正的规则引擎
 * （coord 的 {@code Evaluate}）落地后由宿主提供 {@link PolicyEvaluator} bean 替换本实现，账本与 token 的字段不变。
 *
 * <p>配置不完整时在构造器里失败并点名配置键：策略引用一旦缺版本，守卫侧对不可逆动作只能拒绝，与其到运行期才发现，
 * 不如启动就拦住。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ConfiguredPolicyEvaluator implements PolicyEvaluator {

    private static final String ALLOW = "allow";

    private final PolicyProperties properties;

    private final PolicyRef ref;

    /**
     * 按配置构造。
     *
     * @param properties 策略配置
     * @throws NullPointerException {@code properties} 为 {@code null}
     * @throws IllegalStateException {@code enabled=true} 时 {@code bundle} 或 {@code version} 缺失
     */
    public ConfiguredPolicyEvaluator(PolicyProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.ref = properties.enabled() ? enabledRef(properties) : PolicyRef.none();
    }

    /**
     * 返回钉死的策略引用。
     *
     * @param request 求值输入（本实现不读，规则引擎替换后才会用）
     * @return 策略引用；未启用时为 {@link PolicyRef#none()}
     */
    @Override
    public PolicyRef evaluate(PolicyRequest request) {
        Objects.requireNonNull(request, "request");
        return ref;
    }

    private static PolicyRef enabledRef(PolicyProperties properties) {
        String bundle = properties.bundle();
        if (bundle == null || bundle.isBlank()) {
            throw new IllegalStateException("seedj.ai.policy.bundle 未配置：启用策略时必须声明受管策略包");
        }
        if (properties.version() == null) {
            throw new IllegalStateException(
                    "seedj.ai.policy.version 未配置：启用策略时必须声明当前 head 版本");
        }
        return new PolicyRef(
                properties.engine(),
                bundle,
                properties.version(),
                properties.epoch(),
                ALLOW,
                List.of(),
                null);
    }
}
