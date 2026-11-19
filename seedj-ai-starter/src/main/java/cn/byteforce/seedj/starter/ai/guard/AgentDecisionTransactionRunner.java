package cn.byteforce.seedj.starter.ai.guard;

import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 命令事务装饰器：进入事务后先设置 RLS 需要的 GUC，再执行命令。
 *
 * <p>由 {@code BeanPostProcessor} 包在宿主的 {@link TransactionRunner} 外层；没有决策上下文时完全委托。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class AgentDecisionTransactionRunner implements TransactionRunner {

    private final TransactionRunner delegate;

    private final AgentDecisionGuc guc;

    public AgentDecisionTransactionRunner(TransactionRunner delegate, AgentDecisionGuc guc) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.guc = Objects.requireNonNull(guc, "guc");
    }

    @Override
    public <R> R inTransaction(Supplier<R> work) {
        return delegate.inTransaction(
                () -> {
                    guc.apply();
                    return work.get();
                });
    }
}
