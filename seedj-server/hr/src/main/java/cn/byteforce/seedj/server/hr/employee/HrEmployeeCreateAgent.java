package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.TemporalCommandAgent;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.event.CommandEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 员工建档命令代理：服务端生成工号、派生证件散列。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrEmployeeCreateAgent extends TemporalCommandAgent<HrEmployeeCreate> {

    private final DerivedField derived;

    private final AtomicLong sequence;

    private final ThreadLocal<UUID> allocated = new ThreadLocal<>();

    /**
     * 构造代理。
     *
     * @param runtime  命令运行时
     * @param derived  证件散列派生
     * @param sequence 工号序列
     */
    public HrEmployeeCreateAgent(CommandRuntime runtime, DerivedField derived,
                                 AtomicLong sequence) {
        super(runtime, "hr.employee.create", "employee", HrEmployeeCreate.class);
        this.derived = derived;
        this.sequence = sequence;
    }

    @Override
    protected void validate(HrEmployeeCreate command) {
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
        UUID id = newId();
        allocated.set(id);
        return id;
    }

    @Override
    protected AppendRequest append(HrEmployeeCreate command, Optional<TemporalRow> current,
                                   Instant now, UUID actor) {
        UUID id = allocated.get();
        allocated.remove();
        if (id == null) {
            throw new ContractException(ContractErrorCode.INTERNAL_ERROR,
                    "employee id was not allocated");
        }
        Instant validFrom = command.hireDate() == null || command.hireDate().isBlank()
                ? now : LocalDate.parse(command.hireDate()).atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        String employeeNo = "EMP-%06d".formatted(sequence.incrementAndGet());
        return AppendRequest.create("employee", id, validFrom, null, actor,
                HrEmployeeFields.create(command, employeeNo, derived));
    }

    @Override
    protected List<CommandEvent> events(HrEmployeeCreate command, AppendResult result,
                                        Instant now, UUID actor) {
        return List.of(new CommandEvent(newId(), "hr.employee.created", now, now, actor,
                List.of(result.appended().version().id()),
                HrEmployeeFields.eventPayload(result.appended().fields()),
                "hr.employee.create:hr:1", 1));
    }
}
