package cn.byteforce.seedj.starter.ai.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 准入配置（{@code seedj.ai.guard.admission.*}）。
 *
 * <p>默认开启：有 DataSource 时守卫会把每次已校验的决策写成 admission，命令事务设置 {@code agent.decision_id}， 不可逆表的 RLS
 * 才有东西可核对。没有 DataSource 的内存宿主不装配 admission，启动只 WARN。
 *
 * @param enabled 是否写准入凭证，默认 true
 * @param ttl 准入凭证有效期上限，默认 5m；实际取 {@code min(token.exp, now + ttl)}
 * @param sweep 过期清理间隔，默认 60s
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "seedj.ai.guard.admission")
public record AdmissionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5m") Duration ttl,
        @DefaultValue("60s") Duration sweep) {

    /** {@code ttl} 默认值。 */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    /** {@code sweep} 默认值。 */
    public static final Duration DEFAULT_SWEEP = Duration.ofSeconds(60);

    public AdmissionProperties {
        ttl = ttl == null ? DEFAULT_TTL : ttl;
        sweep = sweep == null ? DEFAULT_SWEEP : sweep;
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("seedj.ai.guard.admission.ttl 必须为正");
        }
        if (sweep.isZero() || sweep.isNegative()) {
            throw new IllegalArgumentException("seedj.ai.guard.admission.sweep 必须为正");
        }
    }
}
