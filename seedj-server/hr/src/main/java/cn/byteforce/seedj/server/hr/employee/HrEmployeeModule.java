package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Maskers;
import cn.byteforce.seedj.domain.Privacy;
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

    @Override
    public void configure(ObjectModules modules) {
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .contextKey(HrScopeKeys.ORG_UNITS)
                .field("employee_no", FieldType.TEXT).required().filterable().sortable().indexed()
                .field("name", FieldType.TEXT).required()
                .field("status", FieldType.TEXT).required().filterable()
                .field("hire_date", FieldType.DATE)
                .field("employment_type", FieldType.TEXT)
                .field("work_location", FieldType.TEXT)
                .field("phone", FieldType.TEXT).privacy(Privacy.SENSITIVE)
                        .mask(Maskers.last4())
                .field("id_number_hash", FieldType.TEXT).writeOnly()
                .field("org_unit_id", FieldType.TEXT).filterable().indexed()
                .query(HrEmployeeView.class)
                    .path("/api/hr/employees")
                    .permission("hr.employee.read")
                    .sort("employee_no");
    }
}
