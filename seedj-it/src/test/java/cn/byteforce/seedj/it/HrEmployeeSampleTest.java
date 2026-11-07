package cn.byteforce.seedj.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.core.security.DerivedFieldKeyRing;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRegistration;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandStatus;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.CommandValidator;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import cn.byteforce.seedj.read.DataScopeResolver;
import cn.byteforce.seedj.read.ReadAuditSink;
import cn.byteforce.seedj.read.ReadGateway;
import cn.byteforce.seedj.read.ReadModel;
import cn.byteforce.seedj.read.ReadModelRegistry;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeCreate;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeFields;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeCreateHandler;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeUpdate;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeUpdateHandler;
import cn.byteforce.seedj.starter.domain.web.ObjectQueryController;
import cn.byteforce.seedj.starter.domain.web.ObjectQueryResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";

    private static final Caller CALLER = new Caller(ACTOR.toString(), "alice", "default",
            Set.of(), Set.of("hr.employee.create", "hr.employee.update", "hr.employee.read"),
            Set.of(), Set.of("pwd"), "urn:seedj:acr:pwd", Map.of());

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = now::get;
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);
    private final ObjectSchema schema = HrEmployeeFields.schema();
    private final DerivedField derived = new DerivedField(
            "seedj:hr:employee:id_number_hash:v1", new DerivedFieldKeyRing("test-key"));
    private final DerivedFieldRegistry derivedFields =
            new DerivedFieldRegistry(Map.of("id_number_hash", derived));
    private final CommandRuntime runtime = new CommandRuntime(
            CommandRuntime.Options.forTests(clock, new Uuid7IdGenerator(clock), commands, objects));
    private final AtomicLong employeeNumbers = new AtomicLong();
    private final CommandRegistration create = registration("hr.employee.create",
            HrEmployeeCreate.class, new HrEmployeeCreateHandler(() -> UUID.randomUUID(),
                    () -> "EMP-%06d".formatted(employeeNumbers.incrementAndGet()),
                    derivedFields, unit -> true));
    private final CommandRegistration update = registration("hr.employee.update",
            HrEmployeeUpdate.class, new HrEmployeeUpdateHandler(derivedFields, unit -> true));
    private final ReadGateway gateway = new ReadGateway(registry(),
            objects, List.of(allScope()), List.of(), ReadAuditSink.noop(), clock,
            UUID::randomUUID);
    private final ObjectQueryController query = new ObjectQueryController(gateway,
            () -> Optional.of(CALLER));

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
        CommandReceipt receipt = create(submission("hr.employee.create", "k-1",
                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", "110101199001011234",
                        "employment_type", "full_time")));

        var row = objects.current("employee", receipt.locator()).orElseThrow();
        assertThat(row.fields()).containsEntry("employee_no", "EMP-000001");
        assertThat(row.fields()).containsEntry("status", "pending_onboarding");
        assertThat(row.fields().get("hire_date")).isInstanceOf(LocalDate.class);
        assertThat(row.fields().get("id_number_hash")).asString().matches("^[0-9a-f]{64}$");
        assertThat(commands.findByIdempotencyKey(ACTOR, "k-1")).get()
                .satisfies(record -> assertThat(record.payload())
                        .doesNotContainKeys("id_number", "id_number_hash"));
    }

    @Test
    @DisplayName("命令 record 字段自动落到 schema，新增字段不再改 handler")
    void commandFieldsFlowIntoSchema() {
        CommandReceipt receipt = create(submission("hr.employee.create", "k-1c",
                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01"),
                        "employment_type", "full_time", "work_location", "SH",
                        "contact", "13800138000", "org_unit_id", ORG_A)));

        var row = objects.current("employee", receipt.locator()).orElseThrow();
        assertThat(row.fields())
                .containsEntry("employment_type", "full_time")
                .containsEntry("work_location", "SH")
                .containsEntry("contact", "13800138000")
                .containsEntry("org_unit_id", UUID.fromString(ORG_A));
    }

    @Test
    @DisplayName("事件白名单不含 writeOnly 字段")
    void eventPayloadOmitsWriteOnlyField() {
        CommandReceipt receipt = create(submission("hr.employee.create", "k-1b",
                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", "110101199001011234")));

        assertThat(schema.eventFields()).doesNotContain("id_number_hash");
        assertThat(objects.current("employee", receipt.locator()).orElseThrow().fields())
                .containsKey("id_number_hash");
    }

    @Test
    @DisplayName("读投影剔除 writeOnly 字段")
    void readProjectionOmitsWriteOnlyField() {
        CommandReceipt receipt = create(submission("hr.employee.create", "k-2",
                Map.of("name", "李四", "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", "110101199001011234")));

        ObjectQueryResponse response = query.find("employee", receipt.locator(), null, null,
                null);

        assertThat(response.data()).doesNotContainKey("id_number_hash");
        assertThat(response.data()).containsEntry("employee_no", "EMP-000001");
    }

    @Test
    @DisplayName("更新保留 employee_no/status，并递增版本")
    void updateKeepsEmployeeNoAndStatus() {
        CommandReceipt created = create(submission("hr.employee.create", "k-3",
                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", "110101199001011234")));
        now.set(T0.plusSeconds(3600));

        CommandReceipt updated = update(submission("hr.employee.update", "k-4",
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
        create(submission("hr.employee.create", "k-5",
                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01"))));
        UUID id = commands.findByIdempotencyKey(ACTOR, "k-5").orElseThrow().locator();
        now.set(T0.plusSeconds(3600));
        update(submission("hr.employee.update", "k-6",
                Map.of("employee_id", id.toString(), "name", "张三丰")));

        var historical = objects.find("employee", id,
                new TemporalQuery(Instant.parse("2026-10-01T12:00:00Z"), null, null),
                ScopePredicate.all()).orElseThrow();
        assertThat(historical.fields()).containsEntry("name", "张三");
        assertThat(historical.version().version()).isEqualTo(1);
    }

    @Test
    @DisplayName("空白 id_number 在派生前拒绝")
    void blankIdNumberIsRejectedBeforeDerivation() {
        assertThatThrownBy(() -> create(submission("hr.employee.create", "k-7",
                Map.of("name", "王五", "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", " "))))
                .isInstanceOf(ContractException.class);
        assertThat(commands.findByIdempotencyKey(ACTOR, "k-7")).get()
                .satisfies(record -> assertThat(record.status())
                        .isEqualTo(CommandStatus.REJECTED));
        assertThat(objects.history("employee", UUID.randomUUID(), ScopePredicate.all())).isEmpty();
    }

    private CommandReceipt create(CommandSubmission submission) {
        return runtime.execute(submission, create, CALLER, ScopePredicate.all());
    }

    private CommandReceipt update(CommandSubmission submission) {
        return runtime.execute(submission, update, CALLER, ScopePredicate.all());
    }

    private CommandRegistration registration(String type, Class<?> payloadType,
                                             cn.byteforce.seedj.cqrs.CommandHandler<?> handler) {
        return new CommandRegistration(CommandDescriptor.builder()
                .type(type)
                .objectType("employee")
                .payloadType(payloadType)
                .permission(type.equals("hr.employee.create")
                        ? "hr.employee.create" : "hr.employee.update")
                .validator(CommandValidator.none())
                .build(), handler, schema,
                java.util.Set.of("id_number", "employee_id"), java.util.Set.of("id_number"));
    }

    private ReadModelRegistry registry() {
        ReadModel model = new ReadModel("employee", schema, "hr.employee.read");
        return new ReadModelRegistry() {
            @Override
            public Optional<ReadModel> find(String objectType) {
                return "employee".equals(objectType) ? Optional.of(model) : Optional.empty();
            }

            @Override
            public List<ReadModel> all() {
                return List.of(model);
            }
        };
    }

    private static DataScopeResolver allScope() {
        return new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return true;
            }

            @Override
            public ScopePredicate resolve(String objectType, Caller caller,
                                          cn.byteforce.seedj.core.security.ScopeContext context) {
                return ScopePredicate.all();
            }
        };
    }

    private static CommandSubmission submission(String type, String key,
                                                Map<String, Object> payload) {
        return new CommandSubmission(type, payload, key, List.of(), ACTOR);
    }
}
