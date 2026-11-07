package cn.byteforce.seedj.cqrs.spi;

import java.util.function.Supplier;

/**
 * 事务边界端口。
 *
 * <p>命令模板需要把"加载 → 处理 → 保存 → 发布"放在同一个事务里，并且让<b>锁在事务外</b> 获取和释放。顺序不能交给业务方：锁在 BEGIN 之后拿、在 COMMIT
 * 之前放，就等于提交那一刻 没有锁，而这个错误在本地永远测不出来。
 *
 * <p>core 不依赖 Spring，所以事务只是一个端口：starter 提供基于 {@code TransactionTemplate} 的实现，内存存储用 {@link
 * #direct()}。
 *
 * <p>{@link #direct()} 的语义是"没有事务"，仅用于内存实现或测试； 生产环境的 JDBC 实现必须提供真正的事务边界。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface TransactionRunner {

    /** 在事务内执行，返回结果；抛出异常时必须回滚。 */
    <R> R inTransaction(Supplier<R> work);

    /** 无事务：直接执行。 */
    static TransactionRunner direct() {
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(Supplier<R> work) {
                return work.get();
            }
        };
    }
}
