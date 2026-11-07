package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AbstractAggregateRoot;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Repository;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventApplier;
import cn.byteforce.seedj.domain.event.EventType;
import cn.byteforce.seedj.domain.exception.AggregateNotFoundException;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.lock.LockErrorCode;
import cn.byteforce.seedj.lock.LockException;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 命令模板的顺序保证。
 *
 * <p>这些断言就是"模板"的全部产出：锁在事务外、事务在锁内、事件先取后存、 保存成功才发布、冲突重试不重复发布、异常路径必释放锁、拦截器只执行一次。
 */
class CommandAgentTest {

    private static final UUID ISSUER = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ObjectSchema SCHEMA =
            ObjectSchema.builder("test_counter", "counter")
                    .field("counter_id", FieldType.TEXT)
                    .field("value", FieldType.INTEGER)
                    .build();

    private static final CommandRegistration REGISTRATION =
            new CommandRegistration(
                    CommandDescriptor.builder()
                            .type("test.counter.increment")
                            .objectType("test_counter")
                            .payloadType(Increment.class)
                            .permission("test.counter.write")
                            .build(),
                    new NoopHandler(),
                    SCHEMA,
                    java.util.Set.of(),
                    java.util.Set.of(),
                    List.of("test.counter.incremented"),
                    false);

    private final List<String> trace = new ArrayList<>();

    private final RecordingPublisher publisher = new RecordingPublisher(trace);

    private final FakeRepository repository = new FakeRepository(trace);

    private final TransactionRunner transactionRunner = new RecordingTransactionRunner(trace);

    @Test
    @DisplayName("新建：加锁 → 事务 → 加载(空) → 创建 → 保存 → 发布 → 提交 → 释放")
    void creationPathOrderIsFixed() {
        CommandRecord record =
                run(
                        agent(
                                "counter-1",
                                CommandAgent.Options.<String>defaults()
                                        .withLockProvider(new RecordingLock(trace))
                                        .withTransactionRunner(transactionRunner)),
                        new Increment("counter-1"));

        assertThat(record.targetRef()).isEqualTo("counter-1");
        assertThat(trace)
                .containsExactly(
                        "lock:acquire:test.counter:counter-1",
                        "tx:begin",
                        "load:counter-1",
                        "create",
                        "save",
                        "publish",
                        "tx:end",
                        "lock:release");
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    @DisplayName("已存在：走 handle，加载到的是重放后的状态")
    void existingAggregateGoesThroughHandle() {
        CounterAgent agent = agent("counter-2", CommandAgent.Options.<String>defaults());
        run(agent, new Increment("counter-2"));
        trace.clear();

        run(agent, new Increment("counter-2"));

        assertThat(trace).containsExactly("load:counter-2", "handle", "save", "publish");
        assertThat(repository.loadedValue("counter-2")).isEqualTo(2);
    }

    @Test
    @DisplayName("修改型命令遇到不存在的流：抛 AggregateNotFoundException，而不是静默创建")
    void modifyCommandDoesNotCreate() {
        CounterAgent agent = agent("counter-3", CommandAgent.Options.<String>defaults(), false);

        assertThatThrownBy(() -> run(agent, new Increment("counter-3")))
                .isInstanceOf(AggregateNotFoundException.class);

        assertThat(trace).containsExactly("load:counter-3");
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("命令类型沿泛型父类解析，创建型由 createsOnEmpty 决定")
    void exposesCommandTypeAndCreates() {
        CounterAgent agent = agent("counter-10", CommandAgent.Options.<String>defaults());

        assertThat(agent.commandType()).isEqualTo(Increment.class);
        assertThat(agent.isCreateCommand()).isTrue();
        assertThat(agent.encodedTarget(new Increment("counter-10"))).isEqualTo("counter-10");
    }

    @Test
    @DisplayName("scope 拒绝当前状态：不保存、不发布")
    void scopeRejectsLoadedAggregateBeforeHandle() {
        CounterAgent agent = agent("counter-11", CommandAgent.Options.<String>defaults());
        run(agent, new Increment("counter-11"));
        trace.clear();
        publisher.published.clear();

        CommandRuntimeTest.FakeCommands scopedStore = new CommandRuntimeTest.FakeCommands();
        AgentInvocation scoped =
                new AgentInvocation(
                        "counter-11",
                        CommandRecord.pending(
                                UUID.randomUUID(),
                                "test.counter.increment",
                                java.time.Instant.parse("2026-11-07T09:00:00Z"),
                                ISSUER,
                                Map.of("counter_id", "counter-11"),
                                "k-scope",
                                "a".repeat(64),
                                java.time.Instant.parse("2026-11-07T09:00:00Z")),
                        SCHEMA,
                        REGISTRATION,
                        ScopePredicate.eq("value", 99),
                        JSON,
                        scopedStore);
        scopedStore.insert(scoped.pending());

        assertThatThrownBy(() -> agent.executeWithin(new Increment("counter-11"), scoped))
                .isInstanceOf(cn.byteforce.seedj.core.exception.ContractException.class);
        assertThat(trace).containsExactly("load:counter-11");
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("事件白名单之外的 DomainEvent 失败关闭")
    void undeclaredEventFailsClosed() {
        CounterAgent agent = agent("counter-12", CommandAgent.Options.<String>defaults());
        CommandRegistration restricted =
                new CommandRegistration(
                        CommandDescriptor.builder()
                                .type("test.counter.increment")
                                .objectType("test_counter")
                                .payloadType(Increment.class)
                                .permission("test.counter.write")
                                .build(),
                        new NoopHandler(),
                        SCHEMA,
                        java.util.Set.of(),
                        java.util.Set.of(),
                        List.of("test.counter.something-else"),
                        false);

        CommandRuntimeTest.FakeCommands restrictedStore = new CommandRuntimeTest.FakeCommands();
        AgentInvocation restrictedContext = invocation("counter-12", restricted, restrictedStore);
        restrictedStore.insert(restrictedContext.pending());
        assertThatThrownBy(
                        () -> agent.executeWithin(new Increment("counter-12"), restrictedContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("undeclared domain event");
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("complete 在事务内、audit 之后执行")
    void completeRunsAfterAuditInsideTransaction() {
        CommandRuntimeTest.FakeCommands store = new CommandRuntimeTest.FakeCommands();
        CounterAgent agent =
                agent(
                        "counter-13",
                        CommandAgent.Options.<String>defaults()
                                .withTransactionRunner(transactionRunner)
                                .withAudit(
                                        new CommandAudit() {
                                            @Override
                                            public void accepted(
                                                    Command<?> command, String resourceId) {
                                                assertThat(trace)
                                                        .contains("tx:begin")
                                                        .doesNotContain("tx:end");
                                                trace.add("audit");
                                            }
                                        }));
        AgentInvocation context = invocation("counter-13", REGISTRATION, store);
        store.insert(context.pending());
        CommandRecord record = agent.executeWithin(new Increment("counter-13"), context);

        assertThat(trace)
                .containsExactly(
                        "tx:begin",
                        "load:counter-13",
                        "create",
                        "save",
                        "publish",
                        "audit",
                        "tx:end");
        assertThat(record.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(store.records).contains(record);
    }

    @Test
    @DisplayName("拦截器在锁与事务之外，且只执行一次")
    void interceptorWrapsLockAndTransaction() {
        List<String> inner = trace;
        CommandInterceptor<String> interceptor =
                invocation -> {
                    inner.add("interceptor:enter");
                    try {
                        return invocation.proceed();
                    } finally {
                        inner.add("interceptor:exit");
                    }
                };
        CounterAgent agent =
                agent(
                        "counter-4",
                        CommandAgent.Options.<String>defaults()
                                .withLockProvider(new RecordingLock(trace))
                                .withTransactionRunner(transactionRunner)
                                .withInterceptors(List.of(interceptor)));

        run(agent, new Increment("counter-4"));

        assertThat(trace)
                .containsExactly(
                        "interceptor:enter",
                        "lock:acquire:test.counter:counter-4",
                        "tx:begin",
                        "load:counter-4",
                        "create",
                        "save",
                        "publish",
                        "tx:end",
                        "lock:release",
                        "interceptor:exit");
    }

    @Test
    @DisplayName("拦截器短路：不加载、不加锁、不发布（幂等命中）")
    void interceptorCanShortCircuit() {
        CommandInterceptor<String> idempotent = invocation -> invocation.aggregateId();
        CounterAgent agent =
                agent(
                        "counter-5",
                        CommandAgent.Options.<String>defaults()
                                .withInterceptors(List.of(idempotent)));

        assertThatThrownBy(() -> run(agent, new Increment("counter-5")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("short-circuited");

        assertThat(trace).isEmpty();
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("并发冲突：重新加载并重新处理，只发布一次，拦截器不重复进入")
    void conflictRetriesWithoutDuplicatingPublish() {
        CommandInterceptor<String> interceptor =
                invocation -> {
                    trace.add("interceptor");
                    return invocation.proceed();
                };
        CounterAgent agent =
                agent(
                        "counter-6",
                        CommandAgent.Options.<String>defaults()
                                .withLockProvider(new RecordingLock(trace))
                                .withTransactionRunner(transactionRunner)
                                .withInterceptors(List.of(interceptor)));
        repository.conflictsRemaining = 1;

        run(agent, new Increment("counter-6"));

        assertThat(trace)
                .containsExactly(
                        "interceptor",
                        "lock:acquire:test.counter:counter-6",
                        "tx:begin",
                        "load:counter-6",
                        "create",
                        "save",
                        "tx:end",
                        "lock:release",
                        "lock:acquire:test.counter:counter-6",
                        "tx:begin",
                        "load:counter-6",
                        "create",
                        "save",
                        "publish",
                        "tx:end",
                        "lock:release");
        assertThat(publisher.published).hasSize(1);
        assertThat(repository.loadedValue("counter-6")).isEqualTo(1);
    }

    @Test
    @DisplayName("锁忙：不进入事务、不加载聚合")
    void busyLockFailsFast() {
        RecordingLock lock = new RecordingLock(trace);
        lock.busy = true;
        CounterAgent agent =
                agent(
                        "counter-7",
                        CommandAgent.Options.<String>defaults()
                                .withLockProvider(lock)
                                .withTransactionRunner(transactionRunner));

        assertThatThrownBy(() -> run(agent, new Increment("counter-7")))
                .isInstanceOfSatisfying(
                        LockException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(LockErrorCode.LOCK_BUSY);
                            assertThat(e.isRetryable()).isTrue();
                        });

        assertThat(trace).containsExactly("lock:acquire:test.counter:counter-7");
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("重试耗尽：抛并发冲突，从未发布")
    void exhaustedRetriesNeverPublish() {
        CounterAgent agent =
                agent(
                        "counter-8",
                        CommandAgent.Options.<String>defaults()
                                .withTransactionRunner(transactionRunner)
                                .withMaxAttempts(2));
        repository.conflictsRemaining = 5;

        assertThatThrownBy(() -> run(agent, new Increment("counter-8")))
                .isInstanceOfSatisfying(
                        CqrsException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CqrsErrorCode.CONCURRENCY_CONFLICT));

        assertThat(publisher.published).isEmpty();
        assertThat(trace.stream().filter("tx:end"::equals)).hasSize(2);
    }

    @Test
    @DisplayName("发布抛异常：事务标记结束，锁仍然释放")
    void releaseHappensWhenPublishFails() {
        RecordingLock lock = new RecordingLock(trace);
        publisher.failOnPublish = true;
        CounterAgent agent =
                agent(
                        "counter-9",
                        CommandAgent.Options.<String>defaults()
                                .withLockProvider(lock)
                                .withTransactionRunner(transactionRunner));

        assertThatThrownBy(() -> run(agent, new Increment("counter-9")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broker down");

        assertThat(trace)
                .containsExactly(
                        "lock:acquire:test.counter:counter-9",
                        "tx:begin",
                        "load:counter-9",
                        "create",
                        "save",
                        "publish",
                        "tx:end",
                        "lock:release");
    }

    private CounterAgent agent(String id, CommandAgent.Options<String> options) {
        return agent(id, options, true);
    }

    private CounterAgent agent(String id, CommandAgent.Options<String> options, boolean creates) {
        return new CounterAgent(
                repository, publisher, IdCodecs.strings(), options, id, creates, trace);
    }

    private static CommandRecord run(CounterAgent agent, Increment command) {
        CommandRuntimeTest.FakeCommands store = new CommandRuntimeTest.FakeCommands();
        AgentInvocation context = invocation(command.counterId(), REGISTRATION, store);
        store.insert(context.pending());
        return agent.executeWithin(command, context);
    }

    private static AgentInvocation invocation(
            String targetRef, CommandRegistration registration, CommandStore store) {
        return new AgentInvocation(
                targetRef,
                CommandRecord.pending(
                        UUID.randomUUID(),
                        "test.counter.increment",
                        java.time.Instant.parse("2026-11-07T09:00:00Z"),
                        ISSUER,
                        Map.of("counter_id", targetRef),
                        "k-" + UUID.randomUUID(),
                        "a".repeat(64),
                        java.time.Instant.parse("2026-11-07T09:00:00Z")),
                SCHEMA,
                registration,
                ScopePredicate.all(),
                JSON,
                store);
    }

    // ---------------------------------------------------------------- fixtures

    record Increment(String counterId) implements Command<String> {
        @Override
        public String aggregateId() {
            return counterId;
        }
    }

    @EventType("test.counter.incremented")
    record Incremented(String counterId) implements DomainEvent {}

    static final class Counter extends AbstractAggregateRoot<String>
            implements EventApplier<Incremented> {

        private int value;

        private Counter(String id) {
            super(id);
        }

        static Counter blank(String id) {
            return new Counter(id);
        }

        static Counter create(String id) {
            Counter counter = new Counter(id);
            counter.raise(new Incremented(id));
            return counter;
        }

        void increment() {
            raise(new Incremented(getId()));
        }

        protected void apply(Incremented event) {
            value++;
        }

        int value() {
            return value;
        }
    }

    static final class NoopHandler implements CommandHandler<Increment> {

        @Override
        public UUID targetId(Increment command) {
            return null;
        }

        @Override
        public cn.byteforce.seedj.domain.AppendRequest decide(
                Increment command, CommandContext ctx) {
            throw new UnsupportedOperationException("not used by agent tests");
        }
    }

    static final class CounterAgent extends CommandAgent<Increment, Counter, String> {

        private final boolean creates;

        private final List<String> trace;

        CounterAgent(
                Repository<Counter, String> repository,
                EventPublisher publisher,
                IdCodec<String> codec,
                CommandAgent.Options<String> options,
                String expectedId,
                boolean creates,
                List<String> trace) {
            super(repository, publisher, codec, options);
            this.creates = creates;
            this.trace = trace;
        }

        @Override
        protected String aggregateType() {
            return "test.counter";
        }

        @Override
        protected boolean createsOnEmpty() {
            return creates;
        }

        @Override
        protected Counter create(String id, Increment command) {
            trace.add("create");
            return Counter.create(id);
        }

        @Override
        protected void handle(Counter aggregate, Increment command) {
            trace.add("handle");
            aggregate.increment();
        }
    }

    static final class FakeRepository implements Repository<Counter, String> {

        private final Map<String, List<DomainEvent>> streams = new HashMap<>();

        private final List<String> trace;

        int conflictsRemaining;

        FakeRepository(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public Optional<Counter> load(String id) {
            List<DomainEvent> history = streams.get(id);
            trace.add("load:" + id);
            if (history == null || history.isEmpty()) {
                return Optional.empty();
            }
            Counter counter = Counter.blank(id);
            counter.replayAll(history, history.size());
            return Optional.of(counter);
        }

        @Override
        public void save(Counter aggregate) {
            trace.add("save");
            if (conflictsRemaining > 0) {
                conflictsRemaining--;
                throw new CqrsException(
                        CqrsErrorCode.CONCURRENCY_CONFLICT,
                        "simulated conflict on " + aggregate.getId());
            }
            List<DomainEvent> history =
                    new ArrayList<>(streams.getOrDefault(aggregate.getId(), List.of()));
            history.addAll(aggregate.peekDomainEvents());
            streams.put(aggregate.getId(), history);
            aggregate.clearDomainEvents();
        }

        int loadedValue(String id) {
            Counter counter = load(id).orElseThrow();
            trace.remove(trace.size() - 1);
            return counter.value();
        }
    }

    static final class RecordingPublisher implements EventPublisher {

        private final List<String> trace;

        final List<DomainEvent> published = new ArrayList<>();

        boolean failOnPublish;

        RecordingPublisher(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public void publish(DomainEvent event) {
            trace.add("publish");
            if (failOnPublish) {
                throw new IllegalStateException("broker down");
            }
            published.add(event);
        }

        @Override
        public void publish(EventMessage message) {
            throw new UnsupportedOperationException("test publisher only accepts domain events");
        }
    }

    static final class RecordingLock implements LockProvider {

        private final List<String> trace;

        boolean busy;

        RecordingLock(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public Optional<LockLease> tryAcquire(String key, java.time.Duration ttl) {
            trace.add("lock:acquire:" + key);
            if (busy) {
                return Optional.empty();
            }
            return Optional.of(LockLease.of(key, () -> trace.add("lock:release")));
        }
    }

    static final class RecordingTransactionRunner implements TransactionRunner {

        private final List<String> trace;

        RecordingTransactionRunner(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public <R> R inTransaction(Supplier<R> work) {
            trace.add("tx:begin");
            try {
                return work.get();
            } finally {
                trace.add("tx:end");
            }
        }
    }
}
