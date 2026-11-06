package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.domain.ObjectSchema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HR 员工字段组装：工号生成、更新合并与事件裁剪。
 *
 * <p>字段校验、类型转换与事件白名单全部来自 {@link ObjectSchema}，本类不再维护第二份字段
 * 列表。证件散列通过逻辑名从 {@link DerivedFieldRegistry} 取，避免多 bean 注入歧义。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class HrEmployeeFields {

    static final String ID_NUMBER_HASH = "id_number_hash";

    private HrEmployeeFields() {}

    static Map<String, Object> create(HrEmployeeCreate command, String employeeNo,
                                      ObjectSchema schema, DerivedFieldRegistry derived) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("employee_no", employeeNo);
        fields.put("name", requireName(command.name()));
        fields.put("status", "pending_onboarding");
        putIfPresent(fields, "hire_date", command.hireDate());
        putIfPresent(fields, "employment_type", command.employmentType());
        putIfPresent(fields, "work_location", command.workLocation());
        putIfPresent(fields, "org_unit_id", command.orgUnitId());
        if (command.idNumber() != null && !command.idNumber().isBlank()) {
            fields.put(ID_NUMBER_HASH, derived.require(ID_NUMBER_HASH).derive(command.idNumber()));
        }
        return schema.validate(fields);
    }

    static Map<String, Object> merge(Map<String, Object> current, HrEmployeeUpdate command,
                                     ObjectSchema schema, DerivedFieldRegistry derived) {
        Map<String, Object> fields = new LinkedHashMap<>(current);
        if (command.name() != null) {
            fields.put("name", requireName(command.name()));
        }
        if (command.employmentType() != null) {
            fields.put("employment_type", command.employmentType());
        }
        if (command.workLocation() != null) {
            fields.put("work_location", command.workLocation());
        }
        if (command.orgUnitId() != null) {
            fields.put("org_unit_id", command.orgUnitId());
        }
        if (command.idNumber() != null && !command.idNumber().isBlank()) {
            fields.put(ID_NUMBER_HASH, derived.require(ID_NUMBER_HASH).derive(command.idNumber()));
        }
        return schema.validate(fields);
    }

    /** 事件载荷只保留 schema 声明的 eventVisible 字段。 */
    static Map<String, Object> eventPayload(ObjectSchema schema, Map<String, Object> fields) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : schema.eventFields()) {
            if (fields.containsKey(key)) {
                safe.put(key, fields.get(key));
            }
        }
        return safe;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "name must not be blank");
        }
        return name;
    }

    private static void putIfPresent(Map<String, Object> fields, String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
    }
}
