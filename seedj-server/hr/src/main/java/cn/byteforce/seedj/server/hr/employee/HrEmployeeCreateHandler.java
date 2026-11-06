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
import java.util.LinkedHashMap;
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
     * @param employeeNos 工号生成器
     * @param derived    派生字段
     * @param orgUnits   组织单元目录
     */
    public HrEmployeeCreateHandler(IdGenerator<UUID> commandIds, IdGenerator<String> employeeNos,
                                   DerivedFieldRegistry derived, OrgUnitDirectory orgUnits) {
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
        if (command.idNumber() != null && command.idNumber().isBlank()) {
            throw reject(ContractErrorCode.INVALID_PAYLOAD, "id_number must not be blank");
        }
        if (command.hireDate() != null
                && command.hireDate().isAfter(LocalDate.ofInstant(ctx.now(), ZoneOffset.UTC))) {
            throw reject(ContractErrorCode.COMMAND_REJECTED,
                    "hire_date must not be in the future");
        }
        String orgUnitId = command.orgUnitId();
        if (orgUnitId == null || orgUnitId.isBlank()) {
            orgUnitId = firstOrgUnit(ctx);
        }
        if (orgUnitId != null && !orgUnitId.isBlank() && !orgUnits.exists(orgUnitId)) {
            throw reject(ContractErrorCode.COMMAND_REJECTED,
                    "unknown org_unit_id: " + orgUnitId);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("employee_no", "EMP-" + employeeNos.id());
        fields.put("name", command.name());
        fields.put("status", "pending_onboarding");
        putIfPresent(fields, "hire_date", command.hireDate());
        putIfPresent(fields, "employment_type", command.employmentType());
        putIfPresent(fields, "work_location", command.workLocation());
        putIfPresent(fields, "phone", command.phone());
        putIfPresent(fields, "org_unit_id", orgUnitId);
        if (command.idNumber() != null && !command.idNumber().isBlank()) {
            fields.put(ID_NUMBER_HASH,
                    derived.require(ID_NUMBER_HASH).derive(command.idNumber()));
        }
        LocalDate hireDate = command.hireDate();
        java.time.Instant validFrom = hireDate == null
                ? ctx.now() : hireDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        return create(ctx, validFrom, fields);
    }

    @Override
    public List<CommandEvent> events(HrEmployeeCreate command, AppendResult result,
                                     CommandContext ctx) {
        return List.of(ctx.event("hr.employee.created",
                HrEmployeeEvents.payload(ctx.schema(), result.appended())));
    }

    private static String firstOrgUnit(CommandContext ctx) {
        Object raw = ctx.caller().attributes().get("org_units");
        if (raw instanceof java.util.Collection<?> values) {
            return values.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).findFirst().orElse(null);
        }
        return null;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "name must not be blank");
        }
    }

    private static void putIfPresent(Map<String, Object> fields, String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
    }
}
