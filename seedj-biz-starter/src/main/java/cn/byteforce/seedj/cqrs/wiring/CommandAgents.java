package cn.byteforce.seedj.cqrs.wiring;

import cn.byteforce.seedj.cqrs.CommandAgent;
import cn.byteforce.seedj.cqrs.CommandAudit;
import cn.byteforce.seedj.cqrs.CommandInterceptor;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 命令模板的装配入口：业务只写 {@code new XxxAgent(..., agents.options())}，
 * 指标拦截器与审计钩子由框架注入。
 *
 * <p>拦截器顺序：框架拦截器（指标）在最外层，业务拦截器按注册顺序排在后面。</p>
 */
public final class CommandAgents {

    private final TransactionRunner transactionRunner;

    private final LockProvider lockProvider;

    private final CommandAudit audit;

    private final CommandMetrics metrics;

    private final List<CommandInterceptor<?>> interceptors;

    private final Duration lockTtl;

    private final int maxAttempts;

    public CommandAgents(TransactionRunner transactionRunner, LockProvider lockProvider,
                         CommandAudit audit, CommandMetrics metrics,
                         List<CommandInterceptor<?>> interceptors) {
        this(transactionRunner, lockProvider, audit, metrics, interceptors,
                Duration.ofSeconds(30), 3);
    }

    public CommandAgents(TransactionRunner transactionRunner, LockProvider lockProvider,
                         CommandAudit audit, CommandMetrics metrics,
                         List<CommandInterceptor<?>> interceptors,
                         Duration lockTtl, int maxAttempts) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.lockProvider = Objects.requireNonNull(lockProvider, "lockProvider");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        this.lockTtl = Objects.requireNonNull(lockTtl, "lockTtl");
        this.maxAttempts = maxAttempts;
    }

    /**
     * 每个命令 Agent 的 {@code Options}。泛型由赋值目标推断：
     * {@code CommandAgent.Options<EmployeeId> options = agents.options();}
     */
    public <ID> CommandAgent.Options<ID> options() {
        List<CommandInterceptor<ID>> ordered = new ArrayList<>(interceptors.size() + 1);
        ordered.add(new CommandMetricsInterceptor<>(metrics));
        for (CommandInterceptor<?> interceptor : interceptors) {
            @SuppressWarnings("unchecked")
            CommandInterceptor<ID> typed = (CommandInterceptor<ID>) interceptor;
            ordered.add(typed);
        }
        return new CommandAgent.Options<>(transactionRunner, lockProvider, lockTtl,
                maxAttempts, ordered, audit);
    }
}
