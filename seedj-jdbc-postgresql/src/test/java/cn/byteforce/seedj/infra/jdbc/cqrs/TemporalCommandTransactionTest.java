package cn.byteforce.seedj.infra.jdbc.cqrs;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRecord;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandStatus;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.CommandContext;
import cn.byteforce.seedj.cqrs.CommandHandler;
import cn.byteforce.seedj.cqrs.CommandRegistration;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.cqrs.TemporalCommandAudit;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.event.CommandEvent;
import cn.byteforce.seedj.infra.jdbc.domain.PostgresTemporalStore;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTables;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalDdl;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTable;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.lock.LockProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对象行与命令终态必须在同一个数据库事务内提交/回滚。
 */
@Testcontainers
class TemporalCommandTransactionTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000011");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static TestClock clock;

    private static PostgresTemporalStore objects;

    private static PostgresCommandStore commands;

    private static CommandRuntime runtime;

    private static CommandRegistration registration;

    private static final Caller CALLER = new Caller(ACTOR.toString(), "alice", "default",
            null, null, null, null, null, null);

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        JdbcMigrationRunner runner = new JdbcMigrationRunner(dataSource);
        new TemporalSchemaCapability(runner).initialize();
        new CommandSchemaCapability(runner).initialize();
        TemporalTable table = new TemporalTable("probe", "probe_object",
                new LinkedHashMap<>(Map.of("name", "TEXT")));
        jdbc.execute(TemporalDdl.createTable(table));

        clock = new TestClock(T0);
        objects = new PostgresTemporalStore(jdbc, clock, new TemporalTables(List.of(table)));
        commands = new PostgresCommandStore(jdbc);
        runtime = new CommandRuntime(new CommandRuntime.Options(
                new SpringRunner(new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource))),
                LockProvider.disabled(), Duration.ofSeconds(30), clock,
                (IdGenerator<UUID>) UUID::randomUUID, commands, objects,
                OutboxWriter.discard(), TemporalCommandAudit.noop()));
        ObjectSchema schema = ObjectSchema.builder("probe", "probe_object")
                .field("name", FieldType.TEXT)
                .build();
        CommandDescriptor descriptor = CommandDescriptor.builder()
                .type("probe.update")
                .objectType("probe")
                .payloadType(ProbeCommand.class)
                .build();
        registration = new CommandRegistration(descriptor, new ProbeHandler(), schema);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE probe_object, command_record");
        clock.set(T0);
    }

    @Test
    @DisplayName("接受命令：对象行与 accepted 命令记录一起提交")
    void acceptedWritesObjectAndCommandInOneTransaction() {
        CommandReceipt receipt = runtime.execute(submission("tx-1", "Alice", false), registration, CALLER,
                ScopePredicate.all());

        assertThat(receipt.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(count("probe_object")).isEqualTo(1);
        assertThat(commands.find(receipt.commandId())).get()
                .satisfies(record -> assertThat(record.status()).isEqualTo(CommandStatus.ACCEPTED));
    }

    @Test
    @DisplayName("追加后拒绝：对象行回滚，rejected 命令记录仍可查")
    void rejectedAfterAppendRollsBackObjectButKeepsRejectedCommand() {
        assertThatThrownBy(() -> runtime.execute(submission("tx-2", "Bob", true), registration, CALLER,
                ScopePredicate.all()))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.TEMPORAL_VIOLATION));

        assertThat(count("probe_object")).isZero();
        CommandRecord record = commands.findByIdempotencyKey(ACTOR, "tx-2").orElseThrow();
        assertThat(record.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(record.rejectionCode()).isEqualTo("EIS-400-003");
    }

    @Test
    @DisplayName("审计失败：对象行与命令终态一起回滚，命令键被释放")
    void auditFailureRollsBackObjectAndCommand() {
        CommandRuntime failing = new CommandRuntime(new CommandRuntime.Options(
                new SpringRunner(new TransactionTemplate(
                        new DataSourceTransactionManager(jdbc.getDataSource()))),
                LockProvider.disabled(), Duration.ofSeconds(30), clock,
                (IdGenerator<UUID>) UUID::randomUUID, commands, objects,
                OutboxWriter.discard(),
                (record, events) -> {
                    throw new IllegalStateException("audit down");
                }));


        assertThatThrownBy(() -> failing.execute(submission("tx-audit", "Alice", false), registration, CALLER,
                ScopePredicate.all()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit down");

        assertThat(count("probe_object")).isZero();
        assertThat(commands.findByIdempotencyKey(ACTOR, "tx-audit")).isEmpty();
    }

    private static int count(String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static CommandSubmission submission(String key, String name, boolean failAfterAppend) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", ID.toString());
        payload.put("name", name);
        payload.put("fail", failAfterAppend);
        return new CommandSubmission("probe.update", payload, key, List.of(ID), ACTOR);
    }

    static final class ProbeHandler implements CommandHandler<ProbeCommand> {

        @Override
        public UUID targetId(ProbeCommand command) {
            return command.target();
        }

        @Override
        public AppendRequest decide(ProbeCommand command, CommandContext ctx) {
            Map<String, Object> fields = Map.of("name", command.name());
            if (ctx.current().isPresent()) {
                return AppendRequest.correction("probe", command.target(), ctx.now(), null,
                        ctx.actor(), fields);
            }
            return AppendRequest.create("probe", command.target(), ctx.now(), null,
                    ctx.actor(), fields);
        }

        @Override
        public List<CommandEvent> events(ProbeCommand command, AppendResult result,
                                         CommandContext ctx) {
            if (command.fail()) {
                throw new ContractException(ContractErrorCode.TEMPORAL_VIOLATION,
                        "failure after append");
            }
            return List.of();
        }
    }

    record ProbeCommand(UUID target, String name, boolean fail) {
    }

    static final class SpringRunner implements TransactionRunner {

        private final TransactionTemplate template;

        SpringRunner(TransactionTemplate template) {
            this.template = template;
        }

        @Override
        public <R> R inTransaction(Supplier<R> work) {
            return template.execute(status -> work.get());
        }
    }
}
