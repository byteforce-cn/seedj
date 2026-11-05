package cn.byteforce.seedj.biz.security;

import cn.byteforce.seedj.core.security.Caller;

import java.util.Optional;

/**
 * 基于 {@link ThreadLocal} 的调用方传播。
 *
 * <p>作用域可嵌套：{@link Scope#close()} 恢复进入前的值，而不是无条件清空，
 * 因此异步任务里再套一层也不会把外层身份抹掉。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ThreadLocalCallerPropagation implements CallerPropagation {

    private final ThreadLocal<Caller> holder = new ThreadLocal<>();

    @Override
    public Optional<Caller> current() {
        return Optional.ofNullable(holder.get());
    }

    @Override
    public Scope open(Caller caller) {
        Caller previous = holder.get();
        if (caller == null) {
            holder.remove();
        } else {
            holder.set(caller);
        }
        return () -> {
            if (previous == null) {
                holder.remove();
            } else {
                holder.set(previous);
            }
        };
    }
}
