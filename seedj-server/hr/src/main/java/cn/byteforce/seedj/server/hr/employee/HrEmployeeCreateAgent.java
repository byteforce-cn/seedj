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
import cn.byteforce.seedj.id.IdGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 员工建档命令代理：目标 ID 由运行时解析后传入，工号由 {@link IdGenerator} 生成。
 *
 * <p>不再使用 {@code ThreadLocal} 在 {@code targetId()} 与 {@code append()} 之间传递状态：
 * 命令运行时已经先算好目标 ID，再以参数交给追加逻辑。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrEmployeeCreateAgent extends TemporalCommandAgent<HrEmployeeCreate> {

    private final ObjectSchema schema;

    private final DerivedFieldRegistry derived;

    private final IdGenerator<String> ids;

    /**
     * 构造代理。
     *
     * @param runtime    命令运行时
     * @param descriptor 命令描述
     * @param schema     员工 schema
     * @param derived    派生字段注册表
     * @param ids        工号生成器
     */
    public HrEmployeeCreateAgent(CommandRuntime runtime, CommandDescriptor descriptor,
                                 ObjectSchema schema, DerivedFieldRegistry derived,
                                 IdGenerator<String> ids) {
        super(runtime, descriptor);
        this.schema = schema;
        this.derived = derived;
        this.ids = ids;
    }

    @Override
    protected void validate(HrEmployeeCreate command) {
        super.validate(command);
        if (command.idNumber() != null && command.idNumber().isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "id_number must not be blank");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "name must not be blank");
        }
    }

    @Override
    protected UUID targetId(HrEmployeeCreate command) {
        return newId();
    }

    @Override
    protected AppendRequest append(HrEmployeeCreate command, Optional<TemporalRow> current,
                                   Instant now, UUID actor) {
        return append(command, targetId(command), current, now, actor);
    }

    @Override
    protected AppendRequest append(HrEmployeeCreate command, UUID targetId,
                                   Optional<TemporalRow> current, Instant now, UUID actor) {
        LocalDate hireDate = command.hireDate();
        Instant validFrom = hireDate == null
                ? now : hireDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        String employeeNo = "EMP-" + ids.id();
        return AppendRequest.create("employee", targetId, validFrom, null, actor,
                HrEmployeeFields.create(command, employeeNo, schema, derived));
    }

    @Override
    protected List<CommandEvent> events(HrEmployeeCreate command, AppendResult result,
                                        Instant now, UUID actor) {
        return List.of(new CommandEvent(newId(), "hr.employee.created", now, now, actor,
                List.of(result.appended().version().id()),
                HrEmployeeFields.eventPayload(schema, result.appended().fields()),
                "hr.employee.create:hr:1", 1));
    }
}
