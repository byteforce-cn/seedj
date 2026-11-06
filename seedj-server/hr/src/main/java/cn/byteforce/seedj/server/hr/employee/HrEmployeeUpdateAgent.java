package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.TemporalCommandAgent;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.event.CommandEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 员工更新命令代理：合并字段、保留工号与状态。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrEmployeeUpdateAgent extends TemporalCommandAgent<HrEmployeeUpdate> {

    private final ObjectSchema schema;

    private final DerivedFieldRegistry derived;

    /**
     * 构造代理。
     *
     * @param runtime    命令运行时
     * @param descriptor 命令描述
     * @param schema     员工 schema
     * @param derived    派生字段注册表
     */
    public HrEmployeeUpdateAgent(CommandRuntime runtime, CommandDescriptor descriptor,
                                 ObjectSchema schema, DerivedFieldRegistry derived) {
        super(runtime, descriptor);
        this.schema = schema;
        this.derived = derived;
    }

    @Override
    protected void validate(HrEmployeeUpdate command) {
        super.validate(command);
        if (command.employeeId() == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "employee_id is required");
        }
        if (command.idNumber() != null && command.idNumber().isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "id_number must not be blank");
        }
    }

    @Override
    protected UUID targetId(HrEmployeeUpdate command) {
        return command.employeeId();
    }

    @Override
    protected AppendRequest append(HrEmployeeUpdate command, Optional<TemporalRow> current,
                                   Instant now, UUID actor) {
        return append(command, targetId(command), current, now, actor);
    }

    @Override
    protected AppendRequest append(HrEmployeeUpdate command, UUID targetId,
                                   Optional<TemporalRow> current, Instant now, UUID actor) {
        TemporalRow row = current.orElseThrow(() -> new ContractException(
                ContractErrorCode.OBJECT_NOT_FOUND, "employee not found"));
        return AppendRequest.correction("employee", row.version().id(), now, null, actor,
                HrEmployeeFields.merge(row.fields(), command, schema, derived));
    }

    @Override
    protected List<CommandEvent> events(HrEmployeeUpdate command, AppendResult result,
                                        Instant now, UUID actor) {
        return List.of(new CommandEvent(newId(), "hr.employee.updated", now, now, actor,
                List.of(result.appended().version().id()),
                HrEmployeeFields.eventPayload(schema, result.appended().fields()),
                "hr.employee.update:hr:1", 1));
    }
}
