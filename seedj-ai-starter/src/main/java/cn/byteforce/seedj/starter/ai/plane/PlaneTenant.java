package cn.byteforce.seedj.starter.ai.plane;

import cn.byteforce.seedj.starter.ai.AiProperties;
import java.util.UUID;

/**
 * 决策面的租户来源：只从 {@code seedj.ai.plane.tenant} 读。
 *
 * <p>上报与对账都要按租户读账本（{@code DecisionLedger#byDecision} 是租户作用域），而 T0 没有租户上下文注入， 租户只能在配置里钉死——与 {@link
 * DecisionService} 的启动期校验同一条思路：缺配置就启动失败，而不是退化成 「所有租户都能读」。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class PlaneTenant {

    private PlaneTenant() {}

    /**
     * 读取配置里钉死的租户。
     *
     * @param properties AI 配置
     * @return 租户标识
     * @throws IllegalStateException {@code seedj.ai.plane.tenant} 缺失
     */
    static UUID of(AiProperties properties) {
        UUID tenant = properties.plane().tenant();
        if (tenant == null) {
            throw new IllegalStateException(
                    "seedj.ai.plane.tenant 未配置：outcome 上报与对账按租户读账本，租户由配置钉死");
        }
        return tenant;
    }
}
