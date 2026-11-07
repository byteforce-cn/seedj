package cn.byteforce.seedj.demo.hr.app;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandStatus;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.demo.hr.domain.EmployeeId;
import cn.byteforce.seedj.id.IdGenerator;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 写模型的应用入口：只构造命令提交，幂等/授权/执行由 {@link CommandCatalog} 统一处理。
 *
 * <p>标识在命令构建之前生成；命令载荷里的 {@code employee_id} 决定聚合标识， 运行时从解码后的命令派生 {@code target_ref}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrCommandService {

    private final CommandCatalog catalog;

    private final IdGenerator<EmployeeId> idGenerator;

    public HrCommandService(CommandCatalog catalog, IdGenerator<EmployeeId> idGenerator) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /**
     * 员工入职。
     *
     * @return 新员工的标识
     */
    public EmployeeId onboard(
            String employeeNo,
            String name,
            String department,
            String position,
            LocalDate hireDate,
            Caller caller) {
        Objects.requireNonNull(caller, "caller");
        EmployeeId employeeId = idGenerator.id();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employee_id", employeeId.value());
        payload.put("employee_no", employeeNo);
        payload.put("name", name);
        payload.put("department", department);
        payload.put("position", position);
        payload.put("hire_date", hireDate);
        CommandSubmission submission =
                new CommandSubmission(
                        "hr.employee.onboard",
                        payload,
                        "onboard-" + UUID.randomUUID(),
                        List.of(),
                        UUID.fromString(caller.id()));
        CommandReceipt receipt = catalog.execute(submission, caller);
        if (receipt.status() != CommandStatus.ACCEPTED) {
            throw new IllegalStateException(
                    "onboard command was not accepted: " + receipt.status());
        }
        return employeeId;
    }
}
