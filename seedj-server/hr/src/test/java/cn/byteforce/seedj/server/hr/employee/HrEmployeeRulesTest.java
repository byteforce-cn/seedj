package cn.byteforce.seedj.server.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.core.security.DerivedFieldKeyRing;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.cqrs.CommandContext;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.test.CommandHarness;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 员工业务规则：纯 JUnit，不启动 Spring。
 *
 * <p>装配只保留业务依赖（工号、派生字段、组织目录）；运行时、存储、幂等与写范围由
 * {@link CommandHarness} 提供。规则测试从分钟级降到毫秒级。</p>
 */
class HrEmployeeRulesTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000011");

    private static final Caller CALLER = new Caller(ACTOR.toString(), "alice", "default",
            Set.of(), Set.of("hr.employee.write", "hr.employee.read"), Set.of(),
            Set.of(Caller.DEFAULT_AMR), Caller.DEFAULT_ACR, Map.of("org_units", List.of("D01")));

    private final AtomicLong employeeNumbers = new AtomicLong();

    private final DerivedFieldRegistry derivedFields = new DerivedFieldRegistry(Map.of(
            HrEmployeeCreateHandler.ID_NUMBER_HASH,
            new DerivedField("seedj:hr:employee:id_number_hash:v1",
                    new DerivedFieldKeyRing("test-key"))));

    private final HrEmployeeCreateHandler create = new HrEmployeeCreateHandler(
            UUID::randomUUID,
            () -> "EMP-%06d".formatted(employeeNumbers.incrementAndGet()),
            derivedFields, unit -> Set.of("D01", "D02").contains(unit));

    private final HrEmployeeUpdateHandler update =
            new HrEmployeeUpdateHandler(derivedFields, unit -> Set.of("D01", "D02").contains(unit));

    private final CommandHarness harness = CommandHarness.of(moduleRegistry(), CALLER)
            .clockAt(T0);

    @Test
    @DisplayName("建档状态固定 pending_onboarding")
    void createStartsPendingOnboarding() {
        CommandReceipt receipt = harness.execute("hr.employee.create",
                Map.of("name", "张三", "org_unit_id", "D01"), "rule-1");

        assertThat(harness.object("employee", receipt.locator()).fields())
                .containsEntry("status", "pending_onboarding")
                .containsEntry("name", "张三");
    }

    @Test
    @DisplayName("离职后禁止改岗位")
    void terminatedEmployeeCannotChangeOrgUnit() {
        UUID id = create("rule-2");
        harness.advance(Duration.ofMinutes(1));
        harness.execute("hr.employee.update",
                Map.of("employee_id", id.toString(), "status", "active"), "rule-2a");
        harness.advance(Duration.ofMinutes(1));
        harness.execute("hr.employee.update",
                Map.of("employee_id", id.toString(), "status", "terminated"), "rule-2b");
        harness.advance(Duration.ofMinutes(1));

        assertThatThrownBy(() -> harness.execute("hr.employee.update",
                Map.of("employee_id", id.toString(), "org_unit_id", "D02"), "rule-2c"))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(ContractErrorCode.COMMAND_REJECTED);
                    assertThat(error.reason()).contains("terminated");
                });
    }

    @Test
    @DisplayName("非法状态迁移被拒绝")
    void invalidStatusTransitionIsRejected() {
        UUID id = create("rule-3");

        assertThatThrownBy(() -> harness.execute("hr.employee.update",
                Map.of("employee_id", id.toString(), "status", "terminated"), "rule-3a"))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(ContractErrorCode.COMMAND_REJECTED);
                    assertThat(error.reason()).contains("transition");
                });
    }

    @Test
    @DisplayName("未来入职日期被拒绝")
    void futureHireDateIsRejected() {
        assertThatThrownBy(() -> harness.execute("hr.employee.create",
                Map.of("name", "赵六", "hire_date", LocalDate.parse("2999-01-01")), "rule-4"))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.COMMAND_REJECTED));
    }

    @Test
    @DisplayName("未知组织单元被拒绝")
    void unknownOrgUnitIsRejected() {
        assertThatThrownBy(() -> harness.execute("hr.employee.create",
                Map.of("name", "钱七", "org_unit_id", "D99"), "rule-5"))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.COMMAND_REJECTED));
    }

    @Test
    @DisplayName("事件幂等键使用命令 ID 并递增")
    void createdEventKeyUsesCommandId() {
        UUID target = UUID.randomUUID();
        CommandContext context = new CommandContext(UUID.randomUUID(), target, Optional.empty(),
                T0, target, CALLER, ScopePredicate.all(), HrEmployeeFields.schema(),
                UUID::randomUUID);
        HrEmployeeCreate command = new HrEmployeeCreate("张三", null, null, null, null, "D01",
                null);
        var request = create.decide(command, context);
        TemporalRow row = new TemporalRow("employee",
                new ObjectVersion(target, 1, context.now(), null, context.now(), null,
                        target, context.now(), null, null), request.fields());

        var events = create.events(command, new AppendResult(row, Optional.empty()), context);

        assertThat(events).singleElement().satisfies(event ->
                assertThat(event.idempotencyKey())
                        .isEqualTo(context.commandId() + ":hr.employee.created:0"));
    }

    private UUID create(String key) {
        return harness.execute("hr.employee.create",
                Map.of("name", "张三", "org_unit_id", "D01"), key).locator();
    }

    private ObjectModuleRegistry moduleRegistry() {
        ObjectModules modules = ObjectModules.create();
        new HrEmployeeModule(create, update).configure(modules);
        return modules.freeze();
    }
}
