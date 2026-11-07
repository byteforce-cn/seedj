package cn.byteforce.seedj.starter.security;

import cn.byteforce.seedj.core.security.Caller;
import java.util.Optional;

/**
 * 调用方作用域传播 seam：把请求线程的身份带到异步线程（{@code @Async}、 自建线程池），形状与 {@code TracePropagation} 保持一致。
 *
 * <p>刻意<b>不</b>传播到事件消息头：{@code EventMessage.headers} 会落库且对 所有消费者可见，租户/用户在 headers 里属于数据泄漏（见 {@code
 * TracePropagation} 的同一条约定）。事件消费端如需审计主体， 应由事件负载显式携带业务主体，而不是透传令牌声明。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface CallerPropagation {

    /** 当前作用域内的调用方。 */
    Optional<Caller> current();

    /** 在返回的作用域内，{@link #current()} 返回给定调用方。 */
    Scope open(Caller caller);

    interface Scope extends AutoCloseable {

        @Override
        void close();
    }

    /** 不传播任何身份：异步线程里 {@link #current()} 恒为空。 */
    static CallerPropagation noop() {
        return new CallerPropagation() {
            @Override
            public Optional<Caller> current() {
                return Optional.empty();
            }

            @Override
            public Scope open(Caller caller) {
                return () -> {};
            }
        };
    }
}
