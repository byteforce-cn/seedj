package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.cqrs.CommandPayloadRedactor;
import cn.byteforce.seedj.module.ObjectModuleConfigurer;
import cn.byteforce.seedj.module.ObjectModules;
import org.springframework.context.annotation.Configuration;

/**
 * 员工实体的唯一读侧声明：schema、读权限、视图查询与组织范围 key。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class HrEmployeeModule implements ObjectModuleConfigurer {

    private final HrEmployeeCreateHandler create;

    private final HrEmployeeUpdateHandler update;

    private final CommandPayloadRedactor redactor;

    /**
     * 构造模块声明。
     *
     * @param create   建档处理器
     * @param update   更新处理器
     * @param redactor 命令记录脱敏
     */
    public HrEmployeeModule(HrEmployeeCreateHandler create, HrEmployeeUpdateHandler update,
                            CommandPayloadRedactor redactor) {
        this.create = create;
        this.update = update;
        this.redactor = redactor;
    }

    @Override
    public void configure(ObjectModules modules) {
        // 字段声明只有一个来源：代码生成器从业务 record 产出的 schema。
        modules.object(HrEmployeeFieldsSchema.schema())
                .readPermission("hr.employee.read")
                .contextKey(HrScopeKeys.ORG_UNITS)
                .command("hr.employee.create", HrEmployeeCreate.class)
                    .permission("hr.employee.write")
                    .creates()
                    .handler(create)
                    .emits("hr.employee.created")
                    .redactor(redactor)
                    .commandField("id_number")
                .command("hr.employee.update", HrEmployeeUpdate.class)
                    .permission("hr.employee.write")
                    .handler(update)
                    .emits("hr.employee.updated")
                    .redactor(redactor)
                    .commandField("employee_id")
                    .commandField("id_number")
                .query(HrEmployeeView.class)
                    .path("/api/hr/employees")
                    .permission("hr.employee.read")
                    .sort("employee_no");
    }
}
