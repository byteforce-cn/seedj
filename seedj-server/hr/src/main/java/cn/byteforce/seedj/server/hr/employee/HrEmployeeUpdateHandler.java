package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.cqrs.AbstractCommandHandler;
import cn.byteforce.seedj.cqrs.CommandContext;
import cn.byteforce.seedj.cqrs.CommandHandler;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.event.CommandEvent;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 员工更新：状态机 {@code pending_onboarding → active → terminated}，离职后禁止改岗位。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrEmployeeUpdateHandler extends AbstractCommandHandler<HrEmployeeUpdate>
        implements CommandHandler<HrEmployeeUpdate> {

    private final DerivedFieldRegistry derived;

    private final OrgUnitDirectory orgUnits;

    /**
     * 构造处理器。
     *
     * @param derived  派生字段
     * @param orgUnits 组织单元目录
     */
    public HrEmployeeUpdateHandler(DerivedFieldRegistry derived, OrgUnitDirectory orgUnits) {
        this.derived = derived;
        this.orgUnits = orgUnits;
    }

    @Override
    public UUID targetId(HrEmployeeUpdate command) {
        return command.employeeId();
    }

    @Override
    public AppendRequest decide(HrEmployeeUpdate command, CommandContext ctx) {
        TemporalRow current = requireCurrent(ctx);
        String currentStatus = string(current.fields().get("status"));
        String requested = command.status();
        if (requested != null && !requested.equals(currentStatus)) {
            if (!allowed(currentStatus, requested)) {
                throw reject(ContractErrorCode.COMMAND_REJECTED,
                        "invalid status transition: " + currentStatus + " -> " + requested);
            }
        }
        if ("terminated".equals(currentStatus)) {
            if (command.orgUnitId() != null) {
                throw reject(ContractErrorCode.COMMAND_REJECTED,
                        "terminated employee cannot change org_unit_id");
            }
            if (requested != null && !"terminated".equals(requested)) {
                throw reject(ContractErrorCode.COMMAND_REJECTED,
                        "terminated employee cannot change status");
            }
        }
        if (command.hireDate() != null
                && command.hireDate().isAfter(LocalDate.ofInstant(ctx.now(), ZoneOffset.UTC))) {
            throw reject(ContractErrorCode.COMMAND_REJECTED,
                    "hire_date must not be in the future");
        }
        if (command.orgUnitId() != null && !command.orgUnitId().isBlank()
                && !orgUnits.exists(command.orgUnitId())) {
            throw reject(ContractErrorCode.COMMAND_REJECTED,
                    "unknown org_unit_id: " + command.orgUnitId());
        }
        if (command.idNumber() != null && command.idNumber().isBlank()) {
            throw reject(ContractErrorCode.INVALID_PAYLOAD, "id_number must not be blank");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        putIfPresent(changes, "name", command.name());
        putIfPresent(changes, "employment_type", command.employmentType());
        putIfPresent(changes, "work_location", command.workLocation());
        putIfPresent(changes, "phone", command.phone());
        putIfPresent(changes, "org_unit_id", command.orgUnitId());
        putIfPresent(changes, "status", command.status());
        putIfPresent(changes, "hire_date", command.hireDate());
        if (command.idNumber() != null && !command.idNumber().isBlank()) {
            changes.put(HrEmployeeCreateHandler.ID_NUMBER_HASH,
                    derived.require(HrEmployeeCreateHandler.ID_NUMBER_HASH)
                            .derive(command.idNumber()));
        }
        return correct(ctx, merge(ctx, changes));
    }

    @Override
    public List<CommandEvent> events(HrEmployeeUpdate command, AppendResult result,
                                     CommandContext ctx) {
        return List.of(ctx.event("hr.employee.updated",
                HrEmployeeEvents.payload(ctx.schema(), result.appended())));
    }

    private static boolean allowed(String current, String next) {
        if (current == null) {
            return true;
        }
        return switch (current) {
            case "pending_onboarding" -> "active".equals(next);
            case "active" -> "terminated".equals(next);
            default -> false;
        };
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static void putIfPresent(Map<String, Object> fields, String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
    }
}
