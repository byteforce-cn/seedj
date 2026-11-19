package cn.byteforce.seedj.starter.ai.policy;

import java.util.Optional;

/**
 * 配置驱动的 head 源：只认识配置里的那一个 {@code engine} + {@code bundle}。
 *
 * <p>其它组合返回空而不是回退到配置版本：守卫面对一个本部署不管理的策略包时，唯一安全的答案是「无法确认新鲜度」。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ConfiguredPolicyHeadSource implements PolicyHeadSource {

    private final PolicyProperties properties;

    private final PolicyHead head;

    /**
     * 按配置构造。
     *
     * @param properties 策略配置
     * @throws NullPointerException {@code properties} 为 {@code null}
     * @throws IllegalStateException {@code enabled=true} 时 {@code bundle} 或 {@code version} 缺失
     */
    public ConfiguredPolicyHeadSource(PolicyProperties properties) {
        this.properties = properties;
        this.head = properties.enabled() ? enabledHead(properties) : null;
    }

    /**
     * 取配置里那一个 bundle 的 head。
     *
     * @param engine 策略引擎名
     * @param bundle 策略包名
     * @return 匹配时返回 head；未启用或组合不认识时为空
     */
    @Override
    public Optional<PolicyHead> head(String engine, String bundle) {
        if (head == null || bundle == null || engine == null) {
            return Optional.empty();
        }
        if (!head.engine().equals(engine) || !head.bundle().equals(bundle)) {
            return Optional.empty();
        }
        return Optional.of(head);
    }

    private static PolicyHead enabledHead(PolicyProperties properties) {
        String bundle = properties.bundle();
        if (bundle == null || bundle.isBlank()) {
            throw new IllegalStateException("seedj.ai.policy.bundle 未配置：启用策略时必须声明受管策略包");
        }
        if (properties.version() == null) {
            throw new IllegalStateException("seedj.ai.policy.version 未配置：启用策略时必须声明当前 head 版本");
        }
        return new PolicyHead(
                properties.engine(), bundle, properties.version(), properties.epoch());
    }
}
