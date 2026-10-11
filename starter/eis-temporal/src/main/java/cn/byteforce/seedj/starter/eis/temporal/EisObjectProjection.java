package cn.byteforce.seedj.starter.eis.temporal;

import cn.byteforce.seedj.starter.eis.core.EisObjectRow;
import cn.byteforce.seedj.starter.eis.core.EisObjectType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读投影：对象行 → 契约响应数据（敏感列剔除）。
 *
 * <p>存储层读取原值（内部流程需要），出参（HTTP/日志）按
 * {@link EisObjectType#getSensitiveFields()} 剔除——脱敏是投影职责。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisObjectProjection {

    private EisObjectProjection() {
    }

    /**
     * 构建 {@code ObjectResponse / ObjectVersion} 形状的响应体。
     *
     * @param row  对象行
     * @param type 对象类型（提供敏感列清单）
     * @return 响应映射（敏感列已剔除）
     */
    public static Map<String, Object> toResponse(EisObjectRow row, EisObjectType type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object_type", row.getObjectType());
        body.put("id", row.getId());
        body.put("version", row.getVersion());
        body.put("valid_from", row.getValidFrom());
        body.put("valid_to", row.getValidTo());
        body.put("recorded_at", row.getRecordedAt());
        body.put("superseded_at", row.getSupersededAt());
        Map<String, Object> data = new LinkedHashMap<>(row.getData());
        for (String sensitive : type.getSensitiveFields()) {
            data.remove(sensitive);
        }
        body.put("data", data);
        return body;
    }
}
