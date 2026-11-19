package cn.byteforce.seedj.starter.ai.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 策略配置（{@code seedj.ai.policy.*}）：T1-local 的求值结果与当前 head 都来自这里。
 *
 * <p>默认关闭：{@code enabled=false} 时决策面仍写 {@code PolicyRef.none()}、守卫不比对策略版本，行为与 T0 逐字一致。
 *
 * <p>单独成一个配置类而不是塞进 {@code AiProperties}：{@code AiProperties} 是公开 record，加组件会改它的规范构造器
 * （对宿主是二进制不兼容）；策略配置只被策略端口读，独立绑定也让「策略是否启用」读起来更直接。
 *
 * @param enabled 是否启用策略版本钉死，默认 false
 * @param engine 策略引擎名，默认 {@value #DEFAULT_ENGINE}
 * @param bundle 受管策略包名；{@code enabled=true} 时必填
 * @param version 当前 head 版本；{@code enabled=true} 时必填
 * @param epoch 当前纪元；可空（引擎不提供时）
 * @param verify 守卫侧校验范围，默认 {@link Verify#IRREVERSIBLE}
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "seedj.ai.policy")
public record PolicyProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("local") String engine,
        String bundle,
        Long version,
        Long epoch,
        @DefaultValue("IRREVERSIBLE") Verify verify) {

    /** {@code seedj.ai.policy.engine} 的默认值：本地无规则，只钉版本。 */
    public static final String DEFAULT_ENGINE = "local";

    public PolicyProperties {
        engine = engine == null || engine.isBlank() ? DEFAULT_ENGINE : engine;
        verify = verify == null ? Verify.IRREVERSIBLE : verify;
    }

    /**
     * 守卫侧的校验范围。
     *
     * <p>{@link #NONE} 只让决策面钉版本、不要求守卫比对（适合「先观察、后强制」的灰度）；{@link #IRREVERSIBLE} 只对两个不可逆档位强制。
     */
    public enum Verify {

        /** 不校验：守卫不读 head（策略引用仍然落账）。 */
        NONE,

        /** 只校验不可逆动作（{@code ActionClass.requiresApproval()} 为 true 的两档）。 */
        IRREVERSIBLE
    }
}
