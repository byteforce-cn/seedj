package cn.byteforce.seedj.starter.ai.effect;

/**
 * 真正调用外部系统的端口；实现必须把 {@code decisionId} 作为幂等键传给外部系统。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface ExternalEffectInvoker {

    /**
     * 调用一次外部系统。
     *
     * @param call 调用输入
     * @return 外部回执
     */
    ExternalEffectResult invoke(ExternalEffectCall call);
}
