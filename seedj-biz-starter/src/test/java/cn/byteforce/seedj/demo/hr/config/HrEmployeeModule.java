package cn.byteforce.seedj.demo.hr.config;

import cn.byteforce.seedj.demo.hr.app.OnboardEmployeeCommand;
import cn.byteforce.seedj.demo.hr.app.OnboardingAgent;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.module.ObjectModuleConfigurer;
import cn.byteforce.seedj.module.ObjectModules;

/**
 * HR demo 的事件溯源命令声明：与双时间轴样板的 {@code ObjectModuleConfigurer} 同形。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrEmployeeModule implements ObjectModuleConfigurer {

    private final OnboardingAgent onboarding;

    public HrEmployeeModule(OnboardingAgent onboarding) {
        this.onboarding = onboarding;
    }

    @Override
    public void configure(ObjectModules modules) {
        modules.object("hr_employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("employee_no", FieldType.TEXT).required()
                .field("name", FieldType.TEXT).required()
                .field("department", FieldType.TEXT).required()
                .field("position", FieldType.TEXT).required()
                .field("hire_date", FieldType.DATE).required()
                .command("hr.employee.onboard", OnboardEmployeeCommand.class)
                    .permission("hr.employee.write")
                    .agent(onboarding)
                    .commandField("employee_id")
                    .emits("hr.employee.onboarded");
    }
}
