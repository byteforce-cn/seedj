package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.domain.AbstractAggregateRoot;
import cn.byteforce.seedj.domain.Repository;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.exception.AggregateNotFoundException;
import cn.byteforce.seedj.eventbus.EventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 命令代理：一条命令的<b>正确执行顺序</b>由本类保证，业务方只实现三个纯函数。
 *
 * <pre>
 * 拦截器 before
 *   acquire(聚合类型:标识, ttl)            ← 锁在事务外
 *     begin transaction
 *       load（空且允许创建 → create；否则 → handle）
 *       events = aggregate.peekDomainEvents()   ← 先取，save 会清空
 *       repository.save(aggregate)             ← 期望版本 CAS
 *       events.forEach(publisher::publish)      ← outbox，与流同事务
 *     commit                                  ← 数据到这里才可见
 *   release
 * 拦截器 after
 * </pre>
 *
 * <p>三个静默失败点由顺序消除，而不是靠文档：
 * <ul>
 *   <li><b>事件丢失</b>：先取后存；</li>
 *   <li><b>半个事务</b>：先存后发，且发布与保存在同一事务内；</li>
 *   <li><b>锁提前释放</b>：锁在 {@code transactionRunner.inTransaction(...)} 之外获取与释放，
 *       提交完成之后才解锁。</li>
 * </ul>
 *
 * <p><b>重试</b>：{@code CONCURRENCY_CONFLICT} 时重新加载并重新 {@link #handle}，最多
 * {@code maxAttempts} 次；拦截器只执行一次，事件只在成功提交后发布一次。
 * {@link #handle} 必须可重复执行（重试会拿到全新实例）。
 *
 * <p><b>聚合不认识 {@link Command}</b>：{@link #handle} 调用聚合自己的业务方法。
 * 领域层不依赖应用层。
 *
 * <p><b>一条命令一条聚合</b>：{@link Command#aggregateId()} 是必填项，多聚合命令在类型层面
 * 写不出来，只能拆 saga。
 *
 * @param <C> 命令类型
 * @param <T> 聚合类型
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class CommandAgent<C extends Command<ID>, T extends AbstractAggregateRoot<ID>, ID> {

    /**
     * 命令代理的装配参数。
     *
     * @param transactionRunner 事务边界（默认无事务，仅适合内存实现）
     * @param lockProvider 分布式锁（默认 {@link LockProvider#disabled()}）
     * @param lockTtl 锁租约，必须大于一次"加载 + 处理 + 保存 + 发布"的耗时
     * @param maxAttempts 并发冲突时的最大尝试次数
     * @param interceptors 拦截器，靠前的在外层
     * @param audit 命令审计 seam；{@link #accepted} 在事务内调用，未启用审计时由装配层注入
     *              {@code DisabledCommandAudit}，不在契约里静默
     * @param <ID> 聚合标识类型
     */
    public record Options<ID>(
            TransactionRunner transactionRunner,
            LockProvider lockProvider,
            Duration lockTtl,
            int maxAttempts,
            List<CommandInterceptor<ID>> interceptors,
            CommandAudit audit) {

        public Options {
            Objects.requireNonNull(transactionRunner, "transactionRunner");
            Objects.requireNonNull(lockProvider, "lockProvider");
            Objects.requireNonNull(audit, "audit");
            if (lockTtl == null || lockTtl.isZero() || lockTtl.isNegative()) {
                throw new IllegalArgumentException("lockTtl must be positive");
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        }

        /**
         * 无锁、无事务、3 次尝试、无审计：只适合内存存储或测试。
         */
        public static <ID> Options<ID> defaults() {
            return new Options<>(TransactionRunner.direct(), LockProvider.disabled(),
                    Duration.ofSeconds(30), 3, List.of(), CommandAudit.noop());
        }

        public Options<ID> withLockProvider(LockProvider lockProvider) {
            return new Options<>(transactionRunner, lockProvider, lockTtl, maxAttempts,
                    interceptors, audit);
        }

        public Options<ID> withTransactionRunner(TransactionRunner transactionRunner) {
            return new Options<>(transactionRunner, lockProvider, lockTtl, maxAttempts,
                    interceptors, audit);
        }

        public Options<ID> withLockTtl(Duration lockTtl) {
            return new Options<>(transactionRunner, lockProvider, lockTtl, maxAttempts,
                    interceptors, audit);
        }

        public Options<ID> withMaxAttempts(int maxAttempts) {
            return new Options<>(transactionRunner, lockProvider, lockTtl, maxAttempts,
                    interceptors, audit);
        }

        public Options<ID> withInterceptors(List<CommandInterceptor<ID>> interceptors) {
            return new Options<>(transactionRunner, lockProvider, lockTtl, maxAttempts,
                    interceptors, audit);
        }

        public Options<ID> withAudit(CommandAudit audit) {
            return new Options<>(transactionRunner, lockProvider, lockTtl, maxAttempts,
                    interceptors, audit);
        }
    }

    private final Repository<T, ID> repository;

    private final EventPublisher publisher;

    private final IdCodec<ID> codec;

    private final Options<ID> options;

    protected CommandAgent(Repository<T, ID> repository, EventPublisher publisher,
                           IdCodec<ID> codec) {
        this(repository, publisher, codec, Options.defaults());
    }

    protected CommandAgent(Repository<T, ID> repository, EventPublisher publisher,
                           IdCodec<ID> codec, Options<ID> options) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * 聚合的稳定逻辑名，用于构造锁键。必须与仓储的流类型名一致。
     */
    protected abstract String aggregateType();

    /**
     * 流不存在时是否允许创建。修改型命令返回 {@code false}（默认），
     * 这样拼错的标识会直接报 {@link AggregateNotFoundException}，而不是静默建出一个新聚合。
     */
    protected boolean createsOnEmpty() {
        return false;
    }

    /**
     * 流不存在且 {@link #createsOnEmpty()} 为真时创建聚合，并把初始事件 {@code raise} 出来。
     */
    protected T create(ID id, C command) {
        throw new AggregateNotFoundException(aggregateType(), id);
    }

    /**
     * 流已存在时让聚合处理命令。必须可重复执行（并发冲突重试会重新调用）。
     */
    protected abstract void handle(T aggregate, C command);

    /**
     * 执行命令。
     *
     * @return 聚合标识
     * @throws CqrsException 锁被占用（{@code LOCK_BUSY}）或重试耗尽（{@code CONCURRENCY_CONFLICT}）
     */
    public final ID execute(C command) {
        Objects.requireNonNull(command, "command");
        ID id = Objects.requireNonNull(command.aggregateId(), "command.aggregateId()");

        CommandInvocation<ID> invocation = new CommandInvocation<>() {
            @Override
            public Command<ID> command() {
                return command;
            }

            @Override
            public ID aggregateId() {
                return id;
            }

            @Override
            public ID proceed() {
                return dispatch(command, id);
            }
        };

        List<CommandInterceptor<ID>> interceptors = options.interceptors();
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            CommandInterceptor<ID> interceptor = interceptors.get(i);
            CommandInvocation<ID> inner = invocation;
            invocation = new CommandInvocation<>() {
                @Override
                public Command<ID> command() {
                    return inner.command();
                }

                @Override
                public ID aggregateId() {
                    return inner.aggregateId();
                }

                @Override
                public ID proceed() {
                    return interceptor.around(inner);
                }
            };
        }
        try {
            return invocation.proceed();
        } catch (RuntimeException e) {
            if (isBusinessRejection(e)) {
                options.audit().rejected(command, codec.encode(id), e);
            }
            throw e;
        }
    }

    /**
     * 业务拒绝（可审计）与技术故障（指标负责）的分界。
     *
     * <p>{@code CONCURRENCY_CONFLICT} 会被模板重试；{@code AuditException} 说明审计
     * 本身失败；存储/序列化异常是技术故障——三者都不算“被拒绝的命令”。</p>
     */
    private static boolean isBusinessRejection(Throwable error) {
        if (error instanceof CqrsException cqrs) {
            return cqrs.getErrorCode() == CqrsErrorCode.COMMAND_REJECTED;
        }
        return error instanceof AggregateNotFoundException;
    }

    private ID dispatch(C command, ID id) {
        String lockKey = aggregateType() + ':' + codec.encode(id);

        for (int attempt = 1; ; attempt++) {
            try (LockLease ignored = acquire(lockKey)) {
                return options.transactionRunner().inTransaction(() -> {
                    T aggregate = loadOrCreate(id, command);
                    // 先取事件再保存：save 成功后聚合会清空待提交列表。
                    List<DomainEvent> events = aggregate.peekDomainEvents();
                    repository.save(aggregate);
                    events.forEach(publisher::publish);
                    // 事务内最后一步：审计写失败 → 命令一起回滚。
                    options.audit().accepted(command, codec.encode(id));
                    return id;
                });
            } catch (CqrsException e) {
                boolean retry = e.getErrorCode() == CqrsErrorCode.CONCURRENCY_CONFLICT
                        && attempt < options.maxAttempts();
                if (!retry) {
                    throw e;
                }
            }
        }
    }

    private T loadOrCreate(ID id, C command) {
        Optional<T> existing = repository.load(id);
        if (existing.isPresent()) {
            T aggregate = existing.get();
            handle(aggregate, command);
            return aggregate;
        }
        if (!createsOnEmpty()) {
            throw new AggregateNotFoundException(aggregateType(), id);
        }
        return create(id, command);
    }

    private LockLease acquire(String lockKey) {
        return options.lockProvider().acquire(lockKey, options.lockTtl());
    }
}
