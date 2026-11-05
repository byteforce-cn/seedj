package cn.byteforce.seedj.cqrs.jdbc;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRecord;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandStatus;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.TemporalCommandAgent;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.event.CommandEvent;
import cn.byteforce.seedj.domain.jdbc.PostgresTemporalStore;
import cn.byteforce.seedj.domain.jdbc.TemporalTables;
import cn.byteforce.seedj.domain.jdbc.TemporalDdl;
import cn.byteforce.seedj.domain.jdbc.TemporalSchemaCapability;
import cn.byteforce.seedj.domain.jdbc.TemporalTable;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.jdbc.JdbcMigrationRunner;
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
import java.util.Optional;
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

    private static ProbeAgent agent;

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
                (IdGenerator<UUID>) UUID::randomUUID, commands, objects, null));
        agent = new ProbeAgent(runtime);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE probe_object, command_record");
        clock.set(T0);
    }

    @Test
    @DisplayName("接受命令：对象行与 accepted 命令记录一起提交")
    void acceptedWritesObjectAndCommandInOneTransaction() {
        CommandReceipt receipt = agent.execute(submission("tx-1", "Alice", false));

        assertThat(receipt.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(count("probe_object")).isEqualTo(1);
        assertThat(commands.find(receipt.commandId())).get()
                .satisfies(record -> assertThat(record.status()).isEqualTo(CommandStatus.ACCEPTED));
    }

    @Test
    @DisplayName("追加后拒绝：对象行回滚，rejected 命令记录仍可查")
    void rejectedAfterAppendRollsBackObjectButKeepsRejectedCommand() {
        assertThatThrownBy(() -> agent.execute(submission("tx-2", "Bob", true)))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.TEMPORAL_VIOLATION));

        assertThat(count("probe_object")).isZero();
        CommandRecord record = commands.findByIdempotencyKey("tx-2").orElseThrow();
        assertThat(record.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(record.rejectionCode()).isEqualTo("EIS-400-003");
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

    static final class ProbeAgent extends TemporalCommandAgent<ProbeCommand> {

        ProbeAgent(CommandRuntime runtime) {
            super(runtime, "probe.update", "probe", ProbeCommand.class);
        }

        @Override
        protected UUID targetId(ProbeCommand command) {
            return command.target();
        }

        @Override
        protected AppendRequest append(ProbeCommand command, Optional<TemporalRow> current,
                                       Instant now, UUID actor) {
            Map<String, Object> fields = Map.of("name", command.name());
            if (current.isPresent()) {
                return AppendRequest.correction("probe", command.target(), now, null, actor,
                        fields);
            }
            return AppendRequest.create("probe", command.target(), now, null, actor, fields);
        }

        @Override
        protected List<CommandEvent> events(ProbeCommand command, AppendResult result,
                                           Instant now, UUID actor) {
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
