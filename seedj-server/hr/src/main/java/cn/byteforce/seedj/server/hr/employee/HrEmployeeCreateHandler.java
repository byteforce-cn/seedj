package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.cqrs.AbstractCommandHandler;
import cn.byteforce.seedj.cqrs.CommandContext;
import cn.byteforce.seedj.cqrs.CommandHandler;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.event.CommandEvent;
import cn.byteforce.seedj.id.IdGenerator;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 员工建档：状态固定 {@code pending_onboarding}，工号由序列生成。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrEmployeeCreateHandler extends AbstractCommandHandler<HrEmployeeCreate>
        implements CommandHandler<HrEmployeeCreate> {

    static final String ID_NUMBER_HASH = "id_number_hash";

    private final IdGenerator<UUID> commandIds;

    private final IdGenerator<String> employeeNos;

    private final DerivedFieldRegistry derived;

    private final OrgUnitDirectory orgUnits;

    /**
     * 构造处理器。
     *
     * @param commandIds 目标对象 ID 生成器
     * @param employeeNos 工号生成器（返回已格式化的 {@code EMP-000001}）
     * @param derived 派生字段
     * @param orgUnits 组织单元目录
     */
    public HrEmployeeCreateHandler(
            IdGenerator<UUID> commandIds,
            IdGenerator<String> employeeNos,
            DerivedFieldRegistry derived,
            OrgUnitDirectory orgUnits) {
        this.commandIds = commandIds;
        this.employeeNos = employeeNos;
        this.derived = derived;
        this.orgUnits = orgUnits;
    }

    @Override
    public UUID targetId(HrEmployeeCreate command) {
        return commandIds.id();
    }

    @Override
    public AppendRequest decide(HrEmployeeCreate command, CommandContext ctx) {
        requireName(command.name());
        if (command.hireDate() == null) {
            throw reject(ContractErrorCode.INVALID_PAYLOAD, "hire_date is required");
        }
        if (command.idNumber() != null && command.idNumber().isBlank()) {
            throw reject(ContractErrorCode.INVALID_PAYLOAD, "id_number must not be blank");
        }
        if (command.hireDate().isAfter(LocalDate.ofInstant(ctx.now(), ZoneOffset.UTC))) {
            throw reject(ContractErrorCode.COMMAND_REJECTED, "hire_date must not be in the future");
        }
        UUID orgUnitId = command.orgUnitId();
        if (orgUnitId == null) {
            orgUnitId = firstOrgUnit(ctx);
        }
        if (orgUnitId != null && !orgUnits.exists(orgUnitId)) {
            throw reject(ContractErrorCode.COMMAND_REJECTED, "unknown org_unit_id: " + orgUnitId);
        }
        Map<String, Object> fields = schemaFields(ctx, command);
        fields.put("employee_no", employeeNos.id());
        fields.put("status", "pending_onboarding");
        if (orgUnitId == null) {
            fields.remove("org_unit_id");
        } else {
            fields.put("org_unit_id", orgUnitId);
        }
        if (command.idNumber() != null && !command.idNumber().isBlank()) {
            fields.put(ID_NUMBER_HASH, derived.require(ID_NUMBER_HASH).derive(command.idNumber()));
        }
        java.time.Instant validFrom = command.hireDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return create(ctx, validFrom, fields);
    }

    @Override
    public List<CommandEvent> events(
            HrEmployeeCreate command, AppendResult result, CommandContext ctx) {
        return List.of(ctx.event("hr.employee.created", result.appended().fields()));
    }

    private static UUID firstOrgUnit(CommandContext ctx) {
        for (String unit : HrScopeKeys.callerOrgUnits(ctx.caller())) {
            try {
                return UUID.fromString(unit);
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 的组织属性不是本服务的组织标识，跳过。
            }
        }
        return null;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "name must not be blank");
        }
    }
}
