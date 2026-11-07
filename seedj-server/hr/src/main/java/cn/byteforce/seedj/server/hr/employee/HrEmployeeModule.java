package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.module.ObjectModuleConfigurer;
import cn.byteforce.seedj.module.ObjectModules;

/**
 * 员工实体的唯一读侧声明：schema、读权限、视图查询与组织范围 key。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class HrEmployeeModule implements ObjectModuleConfigurer {

    private final HrEmployeeCreateHandler create;

    private final HrEmployeeUpdateHandler update;

    /**
     * 构造模块声明。
     *
     * @param create 建档处理器
     * @param update 更新处理器
     */
    public HrEmployeeModule(HrEmployeeCreateHandler create, HrEmployeeUpdateHandler update) {
        this.create = create;
        this.update = update;
    }

    @Override
    public void configure(ObjectModules modules) {
        // 字段声明只有一个来源：代码生成器从业务 record 产出的 schema。
        modules.object(HrEmployeeFieldsSchema.schema())
                .readPermission("hr.employee.read")
                .contextKey(HrScopeKeys.ORG_UNITS)
                .command("hr.employee.create", HrEmployeeCreate.class)
                    .permission("hr.employee.create")
                    .creates()
                    .handler(create)
                    .emits("hr.employee.created")
                    .redactedCommandField("id_number")
                .command("hr.employee.update", HrEmployeeUpdate.class)
                    .permission("hr.employee.update")
                    .handler(update)
                    .emits("hr.employee.updated")
                    .commandField("employee_id")
                    .redactedCommandField("id_number")
                .query(HrEmployeeFields.view())
                    .path("/api/hr/employees")
                    .permission("hr.employee.read")
                    .sort("employee_no");
    }
}
