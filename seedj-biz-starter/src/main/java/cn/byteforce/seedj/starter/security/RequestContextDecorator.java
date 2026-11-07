package cn.byteforce.seedj.starter.security;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.core.security.ScopeContextHolder;
import org.springframework.core.task.TaskDecorator;

import java.util.Objects;

/**
 * 异步装饰器：同时传播 {@link Caller} 与 {@link ScopeContext}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class RequestContextDecorator implements TaskDecorator {

    private final CallerPropagation propagation;

    /**
     * 构造装饰器。
     *
     * @param propagation 调用方传播
     */
    public RequestContextDecorator(CallerPropagation propagation) {
        this.propagation = Objects.requireNonNull(propagation, "propagation");
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        Caller caller = propagation.current().orElse(null);
        ScopeContext context = ScopeContextHolder.current().orElse(null);
        return () -> {
            try (CallerPropagation.Scope callerScope = propagation.open(caller);
                 ScopeContextHolder.Scope contextScope = context == null
                         ? null : ScopeContextHolder.open(context)) {
                runnable.run();
            }
        };
    }
}
