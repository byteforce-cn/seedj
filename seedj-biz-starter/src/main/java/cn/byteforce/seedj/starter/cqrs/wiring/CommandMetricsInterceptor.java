package cn.byteforce.seedj.starter.cqrs.wiring;

import cn.byteforce.seedj.cqrs.Command;
import cn.byteforce.seedj.cqrs.CommandInterceptor;
import cn.byteforce.seedj.cqrs.CommandInvocation;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.domain.exception.AggregateNotFoundException;

import java.time.Duration;

/**
 * 命令指标拦截器：包在锁与事务外面，因此量到的是“锁等待 + 事务 + 处理”的总耗时。
 *
 * <p>由 {@link CommandAgents#options()} 放在拦截器链最外层，业务不需要注册。</p>
 */
public final class CommandMetricsInterceptor<ID> implements CommandInterceptor<ID> {

    private final CommandMetrics metrics;

    public CommandMetricsInterceptor(CommandMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public ID around(CommandInvocation<ID> invocation) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            ID id = invocation.proceed();
            outcome = "accepted";
            return id;
        } catch (RuntimeException e) {
            outcome = classify(e);
            throw e;
        } finally {
            metrics.executed(commandName(invocation.command()),
                    outcome, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    static String commandName(Command<?> command) {
        return command.getClass().getSimpleName();
    }

    static String classify(RuntimeException error) {
        if (error instanceof CqrsException cqrs) {
            return switch (cqrs.getErrorCode()) {
                case COMMAND_REJECTED -> "rejected";
                case CONCURRENCY_CONFLICT -> "conflict";
                default -> "error";
            };
        }
        return error instanceof AggregateNotFoundException ? "rejected" : "error";
    }
}
