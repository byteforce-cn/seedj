package cn.byteforce.seedj.core.security;

import java.util.Objects;
import java.util.Optional;

/**
 * 请求作用域载体：ThreadLocal + try-with-resources，嵌套时恢复进入前的值。
 *
 * <p>Web 入口由过滤器打开；job/消息消费者显式打开。异步边界必须同时传播 {@link Caller} 与 {@link ScopeContext}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ScopeContextHolder {

    private static final ThreadLocal<ScopeContext> CURRENT = new ThreadLocal<>();

    private ScopeContextHolder() {}

    /**
     * 当前上下文。
     *
     * @return 当前快照；未打开为空
     */
    public static Optional<ScopeContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 打开作用域；关闭时恢复进入前的值。
     *
     * @param context 快照
     * @return 可关闭作用域（幂等）
     */
    public static Scope open(ScopeContext context) {
        Objects.requireNonNull(context, "context");
        ScopeContext previous = CURRENT.get();
        CURRENT.set(context);
        return new Scope(previous);
    }

    /** 一次作用域。 */
    public static final class Scope implements AutoCloseable {

        private final ScopeContext previous;

        private boolean closed;

        private Scope(ScopeContext previous) {
            this.previous = previous;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
