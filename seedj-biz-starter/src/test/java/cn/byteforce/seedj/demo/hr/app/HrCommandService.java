package cn.byteforce.seedj.demo.hr.app;

import cn.byteforce.seedj.demo.hr.domain.EmployeeId;
import cn.byteforce.seedj.id.IdGenerator;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 写模型的应用入口，也是<b>标识分配边界</b>。
 *
 * <p>标识在命令构建之前生成，命令因此天然携带目标流；事务与锁的边界在
 * {@link CommandAgent} 内部，本类不再声明事务——否则锁会退化成"在事务内层获取"，
 * 在提交前就被释放。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrCommandService {

    private final OnboardingAgent onboarding;

    private final IdGenerator<EmployeeId> idGenerator;

    public HrCommandService(OnboardingAgent onboarding, IdGenerator<EmployeeId> idGenerator) {
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /**
     * 员工入职。
     *
     * @return 新员工的标识
     */
    public EmployeeId onboard(String employeeNo, String name, String department,
                              String position, LocalDate hireDate) {
        EmployeeId employeeId = idGenerator.id();
        onboarding.execute(new OnboardEmployeeCommand(employeeId, employeeNo, name,
                department, position, hireDate));
        return employeeId;
    }
}
