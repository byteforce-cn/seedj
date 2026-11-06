package cn.byteforce.seedj.server.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.core.security.DerivedFieldKeyRing;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandPayloadRedactor;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.CommandValidator;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 类型化查询模板的 HR 用法：分页、字段映射、无写入专用字段。 */
class HrEmployeeQueryServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = now::get;
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final ObjectSchema schema = new HrEmployeeSchema().schema();
    private final ObjectSchemaRegistry schemas = new ObjectSchemaRegistry(List.of(schema));
    private final TemporalStore objects = new InMemoryTemporalStore(clock, schemas);
    private final DerivedFieldRegistry derived = new DerivedFieldRegistry(Map.of("id_number_hash",
            new DerivedField("seedj:hr:employee:id_number_hash:v1",
                    new DerivedFieldKeyRing("test-key"))));
    private final CommandRuntime runtime = new CommandRuntime(
            CommandRuntime.Options.forTests(clock, new Uuid7IdGenerator(clock), commands, objects));
    private final AtomicLong numbers = new AtomicLong();
    private final IdGenerator<String> ids = () -> "%06d".formatted(numbers.incrementAndGet());
    private final HrEmployeeCreateAgent create = new HrEmployeeCreateAgent(runtime,
            descriptor("hr.employee.create", HrEmployeeCreate.class), schema, derived, ids);
    private final HrEmployeeQueryService service = new HrEmployeeQueryService(objects, schema);

    @BeforeEach
    void reset() {
        now.set(T0);
        commands.clear();
        numbers.set(0);
        ((InMemoryTemporalStore) objects).clear();
    }

    @Test
    @DisplayName("list：分页生效，total 是过滤后的总数")
    void listPagesEmployees() {
        create("张三");
        create("李四");

        Page<HrEmployeeView> page = service.list(new PageRequest(1, 1));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).employeeNo()).isEqualTo("EMP-000001");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("byId：字段映射正确，日期是 LocalDate，视图无证件散列")
    void byIdMapsReadableFields() {
        CommandReceipt receipt = create("张三");

        HrEmployeeView view = service.byId(receipt.locator()).orElseThrow();

        assertThat(view.id()).isEqualTo(receipt.locator());
        assertThat(view.name()).isEqualTo("张三");
        assertThat(view.hireDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(view.status()).isEqualTo("pending_onboarding");
        assertThat(HrEmployeeView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("idNumberHash");
    }

    private CommandReceipt create(String name) {
        return create.execute(new CommandSubmission("hr.employee.create",
                Map.of("name", name, "hire_date", LocalDate.parse("2026-10-01"),
                        "id_number", "110101199001011234"),
                "k-" + name, List.of(), ACTOR));
    }

    private CommandDescriptor descriptor(String type, Class<?> payloadType) {
        return CommandDescriptor.builder()
                .type(type)
                .objectType("employee")
                .payloadType(payloadType)
                .permission("hr.employee.write")
                .validator(CommandValidator.none())
                .redactor(CommandPayloadRedactor.noop())
                .build();
    }
}
