package cn.byteforce.seedj.demo.hr;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.CommandAgent;
import cn.byteforce.seedj.cqrs.CommandAudit;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.TemporalCommandAudit;
import cn.byteforce.seedj.demo.hr.app.HrCommandService;
import cn.byteforce.seedj.demo.hr.app.OnboardingAgent;
import cn.byteforce.seedj.demo.hr.config.HrEmployeeModule;
import cn.byteforce.seedj.demo.hr.domain.Employee;
import cn.byteforce.seedj.demo.hr.domain.EmployeeId;
import cn.byteforce.seedj.demo.hr.domain.EmployeeIdCodec;
import cn.byteforce.seedj.demo.hr.domain.EmployeeRepository;
import cn.byteforce.seedj.demo.hr.domain.EventSourcedEmployeeRepository;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventTypeRegistry;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.cqrs.CommandSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.cqrs.EventSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.cqrs.PostgresCommandStore;
import cn.byteforce.seedj.infra.jdbc.cqrs.PostgresEventStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import cn.byteforce.seedj.infra.local.lock.InMemoryLockProvider;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.starter.cqrs.wiring.SpringTransactionRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HR 事件溯源样板跑在真实 PostgreSQL 上：统一命令信封 → 事件流 → 重开重放 → 幂等。
 *
 * <p>这是“生产 EventStore 已交付”的可执行证据：不是内存 mock，也不是直接调仓储，
 * 而是从 {@link CommandCatalog} 进入，事件写入 {@code event_stream}，命令终态写入
 * {@code command_record}，事务由 {@link SpringTransactionRunner} 保证。</p>
 */
@Testcontainers
class HrOnboardingPostgresTest {

    private static final LocalDate HIRE_DATE = LocalDate.of(2024, 3, 1);

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Caller CALLER = new Caller(ACTOR.toString(), "alice", "default",
            Set.of(), Set.of("hr.employee.write"), Set.of(), Set.of("pwd"),
            Caller.DEFAULT_ACR, Map.of());

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static DataSource dataSource;

    private static JdbcTemplate jdbc;

    private final Clock clock = Clocks.fixed(NOW);

    private final AtomicLong sequence = new AtomicLong();

    private final IdGenerator<EmployeeId> employeeIds =
            () -> EmployeeId.of("emp-" + sequence.incrementAndGet());

    private final EventTypeRegistry eventTypes = new EventTypeRegistry();

    private final RecordingPublisher publisher = new RecordingPublisher();

    private EmployeeRepository employees;

    private CommandRuntime runtime;

    private CommandCatalog catalog;

    private HrCommandService commands;

    @BeforeAll
    static void initializeSchema() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        JdbcMigrationRunner runner = new JdbcMigrationRunner(dataSource);
        new EventSchemaCapability(runner).initialize();
        new CommandSchemaCapability(runner).initialize();
    }

    @BeforeEach
    void wire() {
        jdbc.execute("TRUNCATE event_stream RESTART IDENTITY");
        jdbc.execute("TRUNCATE command_record");
        sequence.set(0);
        publisher.events.clear();

        EmployeeIdCodec codec = new EmployeeIdCodec();
        PostgresEventStore<EmployeeId> events =
                new PostgresEventStore<>(jdbc, codec, eventTypes);
        employees = new EventSourcedEmployeeRepository(events);

        PostgresCommandStore commandStore = new PostgresCommandStore(jdbc);
        TransactionRunner transactions = new SpringTransactionRunner(
                new DataSourceTransactionManager(dataSource));
        LockProvider locks = new InMemoryLockProvider();
        TemporalStore objects = new InMemoryTemporalStore(clock);
        runtime = new CommandRuntime(new CommandRuntime.Options(
                transactions, locks, Duration.ofSeconds(30), clock,
                new Uuid7IdGenerator(clock), commandStore, objects, OutboxWriter.discard(),
                TemporalCommandAudit.noop()));

        OnboardingAgent agent = new OnboardingAgent(employees, publisher, codec,
                CommandAgent.Options.<EmployeeId>defaults()
                        .withTransactionRunner(transactions)
                        .withLockProvider(locks)
                        .withAudit(CommandAudit.noop()));

        ObjectModules modules = ObjectModules.create();
        new HrEmployeeModule(agent).configure(modules);
        ObjectModuleRegistry registry = modules.freeze();
        catalog = new CommandCatalog(registry.commands(), CommandPolicy.permissions(), runtime,
                (objectType, caller) -> ScopePredicate.all());
        commands = new HrCommandService(catalog, employeeIds);
    }

    @Test
    @DisplayName("入职写入 event_stream 与 command_record，重开仓储仍能重放出完整聚合")
    void onboardPersistsAndReplaysFromPostgres() {
        EmployeeId id = commands.onboard("E-001", "张三", "研发中心", "后端工程师", HIRE_DATE,
                CALLER);

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT aggregate_type, stream_key, version, event_type, event_version
                FROM event_stream WHERE aggregate_type = ? AND stream_key = ?
                """, Employee.STREAM_TYPE, id.value());
        assertThat(event.get("version")).isEqualTo(1L);
        assertThat(event.get("event_type")).isEqualTo("hr.employee.onboarded");
        assertThat(event.get("event_version")).isEqualTo(1);

        Map<String, Object> record = jdbc.queryForMap("""
                SELECT status, target_ref, locator, version, event_ids::text AS event_ids
                FROM command_record ORDER BY created_at DESC LIMIT 1
                """);
        assertThat(record.get("status")).isEqualTo("accepted");
        assertThat(record.get("target_ref")).isEqualTo(id.value());
        assertThat(record.get("locator")).isNull();
        assertThat((String) record.get("event_ids")).isNotEqualTo("[]");

        // 全新 store + 全新 registry：只靠仓储注册聚合事件类型就能读回。
        PostgresEventStore<EmployeeId> reopened = new PostgresEventStore<>(
                jdbc, new EmployeeIdCodec(), new EventTypeRegistry());
        Employee loaded = new EventSourcedEmployeeRepository(reopened).loadOrThrow(id);
        assertThat(loaded.name()).isEqualTo("张三");
        assertThat(loaded.department()).isEqualTo("研发中心");
        assertThat(loaded.hireDate()).isEqualTo(HIRE_DATE);
        assertThat(loaded.version()).isEqualTo(1);
        assertThat(publisher.events).hasSize(1);
    }

    @Test
    @DisplayName("同键同载荷重复提交：回放同一 command_id / target_ref，事件流不增加")
    void duplicateSubmissionReplaysWithoutSecondEvent() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employee_id", "emp-idem");
        payload.put("employee_no", "E-002");
        payload.put("name", "李四");
        payload.put("department", "财务");
        payload.put("position", "会计");
        payload.put("hire_date", HIRE_DATE);
        CommandSubmission submission = new CommandSubmission("hr.employee.onboard", payload,
                "pg-idem-1", List.of(), ACTOR);

        CommandReceipt first = catalog.execute(submission, CALLER);
        CommandReceipt second = catalog.execute(submission, CALLER);

        assertThat(second.commandId()).isEqualTo(first.commandId());
        assertThat(second.targetRef()).isEqualTo("emp-idem");
        assertThat(second.version()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM event_stream WHERE stream_key = 'emp-idem'",
                Long.class)).isEqualTo(1L);
    }

    /**
     * 只记录事件，不投递；本测试验证写模型，读模型投影由其它测试覆盖。
     */
    private static final class RecordingPublisher implements EventPublisher {

        private final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }

        @Override
        public void publish(EventMessage message) {
            throw new UnsupportedOperationException("test publisher only accepts domain events");
        }
    }
}
