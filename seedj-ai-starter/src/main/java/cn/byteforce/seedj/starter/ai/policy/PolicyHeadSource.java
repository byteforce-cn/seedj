package cn.byteforce.seedj.starter.ai.policy;

import java.util.Optional;

/**
 * 策略 head 端口：守卫用它回答「当前这一版策略是哪一版」。
 *
 * <p>按 {@code engine} + {@code bundle} 取 head；<b>不认识的组合返回空</b>，守卫据此拒绝不可逆动作（{@code
 * policy_unavailable}）。实现方不要用「取不到就返回最新版」兜底——那会把新鲜度校验变成猜。
 *
 * <p>默认实现是 {@link ConfiguredPolicyHeadSource}：只认识配置里的那一个 bundle。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface PolicyHeadSource {

    /**
     * 取当前 head。
     *
     * @param engine 策略引擎名
     * @param bundle 策略包名
     * @return 当前 head；本部署不认识该组合时为空
     */
    Optional<PolicyHead> head(String engine, String bundle);

    /**
     * 本部署是否参与新鲜度校验。
     *
     * <p>默认 {@code true}；{@link #none()} 返回 {@code false}，守卫据此<b>跳过</b>策略比对（而不是把不可逆动作
     * 全判成 {@code policy_unavailable}）。这个区分让「只钉版本、先不强制」（{@code seedj.ai.policy.verify=none}）与
     * 「启用了但拿不到 head」在守卫侧有不同的答案。
     *
     * @return {@code true} 表示 guarded 动作必须能确认策略新鲜度
     */
    default boolean enforced() {
        return true;
    }

    /**
     * 不参与校验：守卫跳过策略比对，决策面仍可钉版本。
     *
     * @return 常量实现
     */
    static PolicyHeadSource none() {
        return new PolicyHeadSource() {

            @Override
            public Optional<PolicyHead> head(String engine, String bundle) {
                return Optional.empty();
            }

            @Override
            public boolean enforced() {
                return false;
            }
        };
    }
}
