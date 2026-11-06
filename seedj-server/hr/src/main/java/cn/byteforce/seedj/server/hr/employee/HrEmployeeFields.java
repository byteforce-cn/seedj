package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HR 员工字段组装：工号生成、更新合并与事件脱敏。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class HrEmployeeFields {

    private HrEmployeeFields() {
    }

    static Map<String, Object> create(HrEmployeeCreate command, String employeeNo,
                                      DerivedField derived) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("employee_no", employeeNo);
        fields.put("name", requireName(command.name()));
        fields.put("status", "pending_onboarding");
        fields.put("hire_date", command.hireDate());
        putIfPresent(fields, "employment_type", command.employmentType());
        putIfPresent(fields, "work_location", command.workLocation());
        if (command.idNumber() != null && !command.idNumber().isBlank()) {
            fields.put("id_number_hash", derived.derive(command.idNumber()));
        }
        return fields;
    }

    static Map<String, Object> merge(Map<String, Object> current, HrEmployeeUpdate command,
                                     DerivedField derived) {
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
        if (command.idNumber() != null && !command.idNumber().isBlank()) {
            fields.put("id_number_hash", derived.derive(command.idNumber()));
        }
        return fields;
    }

    static Map<String, Object> eventPayload(Map<String, Object> fields) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : new String[]{"employee_no", "name", "status", "hire_date",
                "employment_type", "work_location"}) {
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
