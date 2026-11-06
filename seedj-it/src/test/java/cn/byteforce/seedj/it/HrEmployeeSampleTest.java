package cn.byteforce.seedj.it;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.core.security.DerivedFieldKeyRing;
import cn.byteforce.seedj.cqrs.CommandPayloadRedactor;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.memory.InMemoryCommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.jdbc.TemporalTable;
import cn.byteforce.seedj.domain.jdbc.TemporalTables;
import cn.byteforce.seedj.domain.memory.InMemoryTemporalStore;
import cn.byteforce.seedj.domain.web.ObjectQueryController;
import cn.byteforce.seedj.domain.web.ObjectQueryResponse;
import cn.byteforce.seedj.id.Uuid7IdGenerator;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeCreateAgent;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeUpdateAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HR 活样板：创建/更新、派生字段、读投影剔除与时点查询。
 */
class HrEmployeeSampleTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = now::get;
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);
    private final DerivedField derived = new DerivedField(
            "seedj:hr:employee:id_number_hash:v1", new DerivedFieldKeyRing("test-key"));
    private final CommandPayloadRedactor redactor = payload -> {
        Map<String, Object> redacted = new LinkedHashMap<>(payload);
        redacted.remove("id_number");
        redacted.remove("id_number_hash");
        return redacted;
    };
    private final CommandRuntime runtime = new CommandRuntime(new CommandRuntime.Options(
            TransactionRunner.direct(), LockProvider.disabled(), Duration.ofSeconds(30), clock,
            new Uuid7IdGenerator(clock), commands, objects, null));
    private final HrEmployeeCreateAgent create = new HrEmployeeCreateAgent(runtime, derived,
            new AtomicLong(), redactor);
    private final HrEmployeeUpdateAgent update = new HrEmployeeUpdateAgent(runtime, derived, redactor);
    private final TemporalTables tables = new TemporalTables(List.of(new TemporalTable(
            "employee", "hr_employee",
            Map.of("employee_no", "TEXT", "name", "TEXT", "status", "TEXT",
                    "hire_date", "TEXT", "employment_type", "TEXT",
                    "work_location", "TEXT", "id_number_hash", "TEXT"),
            Set.of("id_number_hash"))));
    private final ObjectQueryController query = new ObjectQueryController(objects, tables);

    @BeforeEach
    void reset() {
        now.set(T0);
        commands.clear();
        objects.clear();
    }

    @Test
    @DisplayName("创建：派生 hash、生成工号，命令记录不含原始/派生值")
    void createDerivesHashAndHidesItFromCommandRecord() {
        CommandReceipt receipt = create.execute(submission("hr.employee.create", "k-1",
                Map.of("name", "张三", "hire_date", "2026-10-01",
                        "id_number", "110101199001011234",
                        "employment_type", "full_time")));

        var row = objects.current("employee", receipt.locator()).orElseThrow();
        assertThat(row.fields()).containsEntry("employee_no", "EMP-000001");
        assertThat(row.fields()).containsEntry("status", "pending_onboarding");
        assertThat(row.fields().get("id_number_hash")).asString()
                .matches("^[0-9a-f]{64}$");
        assertThat(commands.findByIdempotencyKey("k-1")).get().satisfies(record -> {
            assertThat(record.payload()).doesNotContainKeys("id_number", "id_number_hash");
        });
    }

    @Test
    @DisplayName("读投影剔除 writeOnly 字段")
    void readProjectionOmitsWriteOnlyField() {
        CommandReceipt receipt = create.execute(submission("hr.employee.create", "k-2",
                Map.of("name", "李四", "hire_date", "2026-10-01",
                        "id_number", "110101199001011234")));

        ObjectQueryResponse response = query.find("employee", receipt.locator(), null, null, null);

        assertThat(response.data()).doesNotContainKey("id_number_hash");
        assertThat(response.data()).containsEntry("employee_no", "EMP-000001");
    }

    @Test
    @DisplayName("更新保留 employee_no/status，并递增版本")
    void updateKeepsEmployeeNoAndStatus() {
        CommandReceipt created = create.execute(submission("hr.employee.create", "k-3",
                Map.of("name", "张三", "hire_date", "2026-10-01", "id_number", "110101199001011234")));
        now.set(T0.plusSeconds(3600));

        CommandReceipt updated = update.execute(submission("hr.employee.update", "k-4",
                Map.of("employee_id", created.locator().toString(), "name", "张三丰")));

        var row = objects.current("employee", created.locator()).orElseThrow();
        assertThat(row.version().version()).isEqualTo(2);
        assertThat(row.fields()).containsEntry("employee_no", "EMP-000001");
        assertThat(row.fields()).containsEntry("status", "pending_onboarding");
        assertThat(row.fields()).containsEntry("name", "张三丰");
        assertThat(updated.locator()).isEqualTo(created.locator());
    }

    @Test
    @DisplayName("as_of 读取历史版本")
    void asOfReadReturnsVersion() {
        create.execute(submission("hr.employee.create", "k-5",
                Map.of("name", "张三", "hire_date", "2026-10-01")));
        UUID id = commands.findByIdempotencyKey("k-5").orElseThrow().locator();
        now.set(T0.plusSeconds(3600));
        update.execute(submission("hr.employee.update", "k-6",
                Map.of("employee_id", id.toString(), "name", "张三丰")));

        var historical = objects.find("employee", id,
                new TemporalQuery(Instant.parse("2026-10-01T12:00:00Z"), null, null))
                .orElseThrow();
        assertThat(historical.fields()).containsEntry("name", "张三");
        assertThat(historical.version().version()).isEqualTo(1);
    }

    @Test
    @DisplayName("空白 id_number 在派生前拒绝")
    void blankIdNumberIsRejectedBeforeDerivation() {
        assertThatThrownBy(() -> create.execute(submission("hr.employee.create", "k-7",
                Map.of("name", "王五", "hire_date", "2026-10-01", "id_number", " "))))
                .isInstanceOf(ContractException.class);
        assertThat(objects.history("employee", UUID.fromString(
                "00000000-0000-0000-0000-000000000000"))).isEmpty();
    }

    private static CommandSubmission submission(String type, String key,
                                                Map<String, Object> payload) {
        return new CommandSubmission(type, payload, key, List.of(), ACTOR);
    }
}
