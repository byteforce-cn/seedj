package cn.byteforce.seedj.starter.cqrs.wiring;

import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 基于 Spring {@link PlatformTransactionManager} 的事务边界。
 *
 * <p>命令模板要求“锁在事务外、保存与发布在同一事务内”，这个实现只负责把 {@code inTransaction} 映射到 {@link
 * TransactionTemplate#execute}。
 */
public final class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate template;

    public SpringTransactionRunner(PlatformTransactionManager transactionManager) {
        this.template =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public <R> R inTransaction(Supplier<R> work) {
        return template.execute(status -> work.get());
    }
}
