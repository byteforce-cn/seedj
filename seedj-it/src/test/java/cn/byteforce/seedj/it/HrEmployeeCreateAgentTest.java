package cn.byteforce.seedj.it;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.core.security.DerivedFieldKeyRing;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRegistration;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.CommandValidator;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeCreate;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeCreateHandler;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeFields;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 建档 handler：工号来源、日期类型与“无 ThreadLocal 传值”。 */
class HrEmployeeCreateAgentTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Caller CALLER =
            new Caller(
                    ACTOR.toString(),
                    "alice",
                    "default",
                    Set.of(),
                    Set.of("hr.employee.create"),
                    Set.of(),
                    Set.of("pwd"),
                    "urn:seedj:acr:pwd",
                    Map.of());

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = now::get;
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);
    private final DerivedFieldRegistry derived =
            new DerivedFieldRegistry(
                    Map.of(
                            "id_number_hash",
                            new DerivedField(
                                    "seedj:hr:employee:id_number_hash:v1",
                                    new DerivedFieldKeyRing("test-key"))));
    private final IdGenerator<String> ids = () -> "EMP-000001";

    private final CommandRuntime runtime =
            new CommandRuntime(
                    CommandRuntime.Options.forTests(
                            clock, new Uuid7IdGenerator(clock), commands, objects));

    private final CommandRegistration registration =
            new CommandRegistration(
                    CommandDescriptor.builder()
                            .type("hr.employee.create")
                            .objectType("employee")
                            .payloadType(HrEmployeeCreate.class)
                            .permission("hr.employee.create")
                            .validator(CommandValidator.none())
                            .build(),
                    new HrEmployeeCreateHandler(
                            () -> UUID.randomUUID(), ids, derived, unit -> true),
                    HrEmployeeFields.schema());

    @Test
    @DisplayName("工号来自 IdGenerator，不再依赖 AtomicLong 进程内序列")
    void employeeNumberComesFromIdGenerator() {
        CommandReceipt receipt =
                runtime.execute(
                        new CommandSubmission(
                                "hr.employee.create",
                                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01")),
                                "k-1",
                                List.of(),
                                ACTOR),
                        registration,
                        CALLER,
                        ScopePredicate.all());

        assertThat(objects.current("employee", receipt.locator()).orElseThrow().fields())
                .containsEntry("employee_no", "EMP-000001");
    }

    @Test
    @DisplayName("hire_date 是 LocalDate，非法日期在载荷转换期即失败")
    void hireDateIsTypedAndInvalidDateFails() {
        CommandReceipt receipt =
                runtime.execute(
                        new CommandSubmission(
                                "hr.employee.create",
                                Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01")),
                                "k-2",
                                List.of(),
                                ACTOR),
                        registration,
                        CALLER,
                        ScopePredicate.all());

        assertThat(
                        objects.current("employee", receipt.locator())
                                .orElseThrow()
                                .fields()
                                .get("hire_date"))
                .isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    @DisplayName("handler 不含 ThreadLocal 字段")
    void agentHasNoThreadLocalState() {
        for (Field field : HrEmployeeCreateHandler.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("字段 %s 不得使用 ThreadLocal 传递业务状态", field.getName())
                    .isNotEqualTo(ThreadLocal.class.getName());
        }
    }
}
