package cn.byteforce.seedj.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.core.security.DerivedFieldKeyRing;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandPayloadRedactor;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandStatus;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.CommandValidator;
import cn.byteforce.seedj.cqrs.memory.InMemoryCommandStore;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.jdbc.TemporalTable;
import cn.byteforce.seedj.domain.jdbc.TemporalTables;
import cn.byteforce.seedj.domain.memory.InMemoryTemporalStore;
import cn.byteforce.seedj.domain.web.ObjectQueryController;
import cn.byteforce.seedj.domain.web.ObjectQueryResponse;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.id.Uuid7IdGenerator;
import cn.byteforce.seedj.it.fixture.hr.HrEmployeeTestSchema;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeCreate;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeCreateAgent;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeUpdate;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeUpdateAgent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HR 活样板：创建/更新、派生字段、读投影剔除与时点查询。 */
class HrEmployeeSampleTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = now::get;
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);
    private final ObjectSchema schema = HrEmployeeTestSchema.schema();
    private final DerivedField derived = new DerivedField(
            "seedj:hr:employee:id_number_hash:v1", new DerivedFieldKeyRing("test-key"));
    private final DerivedFieldRegistry derivedFields =
            new DerivedFieldRegistry(Map.of("id_number_hash", derived));
    private final CommandPayloadRedactor redactor = payload -> {
        Map<String, Object> redacted = new LinkedHashMap<>(payload);
        redacted.remove("id_number");
        redacted.remove("id_number_hash");
        return redacted;
    };
    private final CommandRuntime runtime = new CommandRuntime(
            CommandRuntime.Options.forTests(clock, new Uuid7IdGenerator(clock), commands, objects));
    private final AtomicLong employeeNumbers = new AtomicLong();
    private final IdGenerator<String> ids =
            () -> "%06d".formatted(employeeNumbers.incrementAndGet());
    private final HrEmployeeCreateAgent create = new HrEmployeeCreateAgent(runtime,
            descriptor("hr.employee.create", HrEmployeeCreate.class), schema, derivedFields, ids);
    private final HrEmployeeUpdateAgent update = new HrEmployeeUpdateAgent(runtime,
            descriptor("hr.employee.update", HrEmployeeUpdate.class), schema, derivedFields);
    private final TemporalTables tables =
            new TemporalTables(List.of(TemporalTable.from(schema)));
    private final ObjectQueryController query = new ObjectQueryController(objects, tables);

    @BeforeEach
    void reset() {
        now.set(T0);
        commands.clear();
        objects.clear();
        employeeNumbers.set(0);
    }

    @Test
    @DisplayName("创建：派生 hash、工号来自 IdGenerator，命令记录不含原始/派生值")
    void createDerivesHashAndHidesItFromCommandRecord() {
        CommandReceipt receipt = create.execute(submission("hr.employee.create", "k-1",
                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", "110101199001011234",
                        "employment_type", "full_time")));

        var row = objects.current("employee", receipt.locator()).orElseThrow();
        assertThat(row.fields()).containsEntry("employee_no", "EMP-000001");
        assertThat(row.fields()).containsEntry("status", "pending_onboarding");
        assertThat(row.fields().get("hire_date")).isInstanceOf(LocalDate.class);
        assertThat(row.fields().get("id_number_hash")).asString().matches("^[0-9a-f]{64}$");
        assertThat(commands.findByIdempotencyKey("k-1")).get()
                .satisfies(record -> assertThat(record.payload())
                        .doesNotContainKeys("id_number", "id_number_hash"));
    }

    @Test
    @DisplayName("事件白名单不含 writeOnly 字段")
    void eventPayloadOmitsWriteOnlyField() {
        CommandReceipt receipt = create.execute(submission("hr.employee.create", "k-1b",
                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", "110101199001011234")));

        assertThat(schema.eventFields()).doesNotContain("id_number_hash");
        assertThat(objects.current("employee", receipt.locator()).orElseThrow().fields())
                .containsKey("id_number_hash");
    }

    @Test
    @DisplayName("读投影剔除 writeOnly 字段")
    void readProjectionOmitsWriteOnlyField() {
        CommandReceipt receipt = create.execute(submission("hr.employee.create", "k-2",
                Map.of("name", "李四", "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", "110101199001011234")));

        ObjectQueryResponse response = query.find("employee", receipt.locator(), null, null, null);

        assertThat(response.data()).doesNotContainKey("id_number_hash");
        assertThat(response.data()).containsEntry("employee_no", "EMP-000001");
    }

    @Test
    @DisplayName("更新保留 employee_no/status，并递增版本")
    void updateKeepsEmployeeNoAndStatus() {
        CommandReceipt created = create.execute(submission("hr.employee.create", "k-3",
                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", "110101199001011234")));
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
                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01"))));
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
                Map.of("name", "王五", "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", " "))))
                .isInstanceOf(ContractException.class);
        assertThat(commands.findByIdempotencyKey("k-7")).get()
                .satisfies(record -> assertThat(record.status())
                        .isEqualTo(CommandStatus.REJECTED));
        assertThat(objects.history("employee", ACTOR)).isEmpty();
    }

    private CommandDescriptor descriptor(String type, Class<?> payloadType) {
        return CommandDescriptor.builder()
                .type(type)
                .objectType("employee")
                .payloadType(payloadType)
                .permission("hr.employee.write")
                .validator(CommandValidator.none())
                .redactor(redactor)
                .build();
    }

    private static CommandSubmission submission(String type, String key,
                                                Map<String, Object> payload) {
        return new CommandSubmission(type, payload, key, List.of(), ACTOR);
    }
}
