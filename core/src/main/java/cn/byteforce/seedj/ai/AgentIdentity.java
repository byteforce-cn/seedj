package cn.byteforce.seedj.ai;

import java.util.Objects;
import java.util.UUID;

/**
 * 发起决策的主体：谁在代理谁。
 *
 * <p>{@code actor} 是真正的执行主体（agent 的身份），{@code onBehalfOf} 是被代理的用户，{@code proxyBindingId}
 * 是代理关系凭据。守卫比对 token 与命令请求时三个一起比——只看 {@code actor} 会让代理关系被换掉。
 *
 * @param actor 执行主体（agent）标识
 * @param onBehalfOf 被代理用户标识；{@code null} 表示不是代理调用
 * @param proxyBindingId 代理解绑定的标识；{@code null} 表示无绑定
 * @param actingService 发起调用的服务名；{@code null} 表示未声明
 * @author ByteForce
 * @since 1.0.0
 */
public record AgentIdentity(UUID actor, UUID onBehalfOf, UUID proxyBindingId, String actingService) {

    /**
     * 校验执行主体。
     *
     * @throws NullPointerException {@code actor} 为 {@code null}
     */
    public AgentIdentity {
        Objects.requireNonNull(actor, "actor");
    }
}
