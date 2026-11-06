package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.TemporalRow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件载荷只保留 schema 的 eventVisible 字段。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class HrEmployeeEvents {

    private HrEmployeeEvents() {
    }

    static Map<String, Object> payload(ObjectSchema schema, TemporalRow row) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String field : schema.eventFields()) {
            if (row.fields().containsKey(field)) {
                safe.put(field, row.fields().get(field));
            }
        }
        return safe;
    }
}
