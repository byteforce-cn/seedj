package cn.byteforce.seedj.demo.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.CommandAgent;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.QueryAgent;
import cn.byteforce.seedj.cqrs.StreamId;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.demo.hr.app.HrCommandService;
import cn.byteforce.seedj.demo.hr.app.OnboardingAgent;
import cn.byteforce.seedj.demo.hr.config.HrEmployeeModule;
import cn.byteforce.seedj.demo.hr.domain.Employee;
import cn.byteforce.seedj.demo.hr.domain.EmployeeId;
import cn.byteforce.seedj.demo.hr.domain.EmployeeIdCodec;
import cn.byteforce.seedj.demo.hr.domain.EmployeeOnboarded;
import cn.byteforce.seedj.demo.hr.domain.EmployeeRepository;
import cn.byteforce.seedj.demo.hr.domain.EventSourcedEmployeeRepository;
import cn.byteforce.seedj.demo.hr.query.EmployeeDirectory;
import cn.byteforce.seedj.demo.hr.query.EmployeeDirectoryProjection;
import cn.byteforce.seedj.demo.hr.query.EmployeeView;
import cn.byteforce.seedj.demo.hr.query.HrQueryService;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventTypeRegistry;
import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryEventStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 入职全链路：命令目录 → 事件流 → 投影 → 查询代理。 */
class HrOnboardingTest {

    private static final LocalDate HIRE_DATE = LocalDate.of(2024, 3, 1);

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Caller CALLER =
            new Caller(
                    ACTOR.toString(),
                    "alice",
                    "default",
                    Set.of(),
                    Set.of("hr.employee.write"),
                    Set.of(),
                    Set.of("pwd"),
                    Caller.DEFAULT_ACR,
                    Map.of());

    private final EmployeeDirectory directory = new EmployeeDirectory();

    private final EmployeeDirectoryProjection projection =
            new EmployeeDirectoryProjection(directory);

    private final InMemoryEventStore<EmployeeId> store =
            new InMemoryEventStore<>(new EmployeeIdCodec());

    private final EmployeeRepository employees = new EventSourcedEmployeeRepository(store);

    private final AtomicLong sequence = new AtomicLong();

    private final IdGenerator<EmployeeId> employeeIds =
            () -> EmployeeId.of("emp-" + sequence.incrementAndGet());

    private final RecordingLock lock = new RecordingLock();

    private final Clock clock = Clocks.fixed(NOW);

    private final CommandStore commandStore = new InMemoryCommandStore();

    private final TemporalStore objects = new InMemoryTemporalStore(clock);

    private final CommandRuntime runtime =
            new CommandRuntime(
                    CommandRuntime.Options.forTests(
                            clock, new Uuid7IdGenerator(clock), commandStore, objects));

    private final OnboardingAgent onboarding =
            new OnboardingAgent(
                    employees,
                    new InProcessPublisher(projection),
                    new EmployeeIdCodec(),
                    CommandAgent.Options.<EmployeeId>defaults().withLockProvider(lock));

    private final ObjectModuleRegistry registry = registry();

    private final CommandCatalog catalog =
            new CommandCatalog(
                    registry.commands(),
                    CommandPolicy.permissions(),
                    runtime,
                    (objectType, caller) -> ScopePredicate.all());

    private final HrCommandService commands = new HrCommandService(catalog, employeeIds);

    private final HrQueryService queries =
            new HrQueryService(directory, new QueryAgent<>(), new QueryAgent<>());

    @BeforeEach
    void resetSequence() {
        sequence.set(0);
    }

    @Test
    @DisplayName("入职：事件落流，读模型可查")
    void onboardWritesStreamAndProjection() {
        EmployeeId id = commands.onboard("E-001", "张三", "研发中心", "后端工程师", HIRE_DATE, CALLER);

        EmployeeView view = queries.byId(id).orElseThrow();
        assertThat(view.employeeNo()).isEqualTo("E-001");
        assertThat(view.name()).isEqualTo("张三");
        assertThat(view.department()).isEqualTo("研发中心");
        assertThat(view.hireDate()).isEqualTo(HIRE_DATE);

        var stream = store.read(StreamId.of(Employee.STREAM_TYPE, id));
        assertThat(stream.version()).isEqualTo(1);
        assertThat(stream.events())
                .containsExactly(
                        new EmployeeOnboarded(id, "E-001", "张三", "研发中心", "后端工程师", HIRE_DATE));
    }

    @Test
    @DisplayName("加载：聚合从事件流重建，版本与待提交事件都正确")
    void aggregateRebuildsFromStream() {
        EmployeeId id = commands.onboard("E-002", "李四", "研发中心", "后端工程师", HIRE_DATE, CALLER);

        Employee loaded = employees.loadOrThrow(id);

        assertThat(loaded.version()).isEqualTo(1);
        assertThat(loaded.expectedVersion()).isEqualTo(1);
        assertThat(loaded.hasUncommittedEvents()).isFalse();
        assertThat(loaded.employeeNo()).isEqualTo("E-002");
        assertThat(loaded.name()).isEqualTo("李四");
    }

    @Test
    @DisplayName("命令按聚合加锁：键为 聚合类型:标识，执行后释放")
    void commandAcquiresAggregateScopedLock() {
        EmployeeId id = commands.onboard("E-003", "王五", "研发中心", "后端工程师", HIRE_DATE, CALLER);

        assertThat(lock.acquiredKeys).containsExactly("hr.employee:" + id.value());
        assertThat(lock.released).isEqualTo(1);
    }

    @Test
    @DisplayName("同一员工标识再次执行入职：命令被拒绝，不产生第二条流")
    void onboardingAnExistingEmployeeIsRejected() {
        EmployeeId id = commands.onboard("E-004", "赵六", "财务", "会计", HIRE_DATE, CALLER);

        assertThatThrownBy(() -> submit(id, "E-099", "冒名", "财务", "会计"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.COMMAND_REJECTED));

        assertThat(store.read(StreamId.of(Employee.STREAM_TYPE, id)).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("重复投递同一事件：读模型仍然只有一行")
    void duplicateDeliveryIsIdempotent() {
        EmployeeId id = commands.onboard("E-005", "孙八", "研发中心", "后端工程师", HIRE_DATE, CALLER);
        EmployeeOnboarded event =
                (EmployeeOnboarded)
                        store.read(StreamId.of(Employee.STREAM_TYPE, id)).events().get(0);

        projection.onEvent(envelope(event, "evt-dup"));
        projection.onEvent(envelope(event, "evt-dup"));

        assertThat(queries.list()).hasSize(1);
        assertThat(queries.byId(id)).isPresent();
    }

    @Test
    @DisplayName("同一标识重复落库：并发冲突，事件流不被污染")
    void duplicateOnboardingConflicts() {
        EmployeeId id = EmployeeId.of("emp-dup");
        Employee first = Employee.onboard(id, "E-006", "周九", "财务", "会计", HIRE_DATE);
        Employee second = Employee.onboard(id, "E-007", "吴十", "法务", "顾问", HIRE_DATE);

        employees.save(first);

        assertThatThrownBy(() -> employees.save(second))
                .isInstanceOfSatisfying(
                        cn.byteforce.seedj.cqrs.CqrsException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(
                                                cn.byteforce.seedj.cqrs.CqrsErrorCode
                                                        .CONCURRENCY_CONFLICT));

        assertThat(store.read(StreamId.of(Employee.STREAM_TYPE, id)).version()).isEqualTo(1);
        assertThat(employees.loadOrThrow(id).employeeNo()).isEqualTo("E-006");
    }

    @Test
    @DisplayName("保存无待提交事件的聚合是 no-op")
    void saveWithoutEventsIsNoOp() {
        EmployeeId id = commands.onboard("E-008", "郑十一", "研发中心", "后端工程师", HIRE_DATE, CALLER);
        Employee loaded = employees.loadOrThrow(id);

        employees.save(loaded);

        assertThat(store.read(StreamId.of(Employee.STREAM_TYPE, id)).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("查询未知员工返回空")
    void unknownEmployeeIsEmpty() {
        assertThat(queries.byId(EmployeeId.of("emp-404"))).isEmpty();
        assertThat(queries.list()).isEmpty();
    }

    private ObjectModuleRegistry registry() {
        ObjectModules modules = ObjectModules.create();
        new HrEmployeeModule(onboarding).configure(modules);
        return modules.freeze();
    }

    private void submit(
            EmployeeId id, String employeeNo, String name, String department, String position) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employee_id", id.value());
        payload.put("employee_no", employeeNo);
        payload.put("name", name);
        payload.put("department", department);
        payload.put("position", position);
        payload.put("hire_date", HIRE_DATE);
        catalog.execute(
                new CommandSubmission(
                        "hr.employee.onboard",
                        payload,
                        "manual-" + UUID.randomUUID(),
                        List.of(),
                        ACTOR),
                CALLER);
    }

    private static EventEnvelope<EmployeeOnboarded> envelope(
            EmployeeOnboarded event, String eventId) {
        return new EventEnvelope<>(
                event,
                eventId,
                EventTypeRegistry.logicalName(EmployeeOnboarded.class),
                "demo.hr.employee.directory",
                1,
                Instant.now(),
                Map.of());
    }

    /** 记录锁的获取键与释放次数：验证模板把锁包在事务外层，且键是聚合级的。 */
    private static final class RecordingLock implements LockProvider {

        private final List<String> acquiredKeys = new ArrayList<>();

        private int released;

        @Override
        public Optional<LockLease> tryAcquire(String key, Duration ttl) {
            acquiredKeys.add(key);
            return Optional.of(LockLease.of(key, () -> released++));
        }
    }

    /** 进程内投递：代替 outbox + 消费者容器，让读侧在同一测试里追平。 */
    private static final class InProcessPublisher implements EventPublisher {

        private final EmployeeDirectoryProjection projection;

        private final AtomicLong delivered = new AtomicLong();

        private InProcessPublisher(EmployeeDirectoryProjection projection) {
            this.projection = projection;
        }

        @Override
        public void publish(DomainEvent event) {
            if (!(event instanceof EmployeeOnboarded onboarded)) {
                throw new UnsupportedOperationException("unexpected event: " + event);
            }
            projection.onEvent(envelope(onboarded, "evt-" + delivered.incrementAndGet()));
        }

        @Override
        public void publish(EventMessage message) {
            throw new UnsupportedOperationException("test publisher only accepts domain events");
        }
    }
}
