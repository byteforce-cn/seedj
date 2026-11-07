package cn.byteforce.seedj.starter.domain.web;

import cn.byteforce.seedj.domain.TemporalRow;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 对象查询响应（线格式使用 snake_case）。
 *
 * @param objectType 对象类型
 * @param id 对象 ID
 * @param version 对象版本
 * @param validFrom 业务生效时间
 * @param validTo 业务失效时间
 * @param recordedAt 系统记录时间
 * @param supersededAt 系统替代时间
 * @param data 领域数据
 * @author ByteForce
 * @since 1.0.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ObjectQueryResponse(
        String objectType,
        UUID id,
        long version,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt,
        Instant supersededAt,
        Map<String, Object> data) {

    public ObjectQueryResponse {
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(recordedAt, "recordedAt");
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /**
     * 从对象行映射。
     *
     * @param row 对象行
     * @return 查询响应
     */
    public static ObjectQueryResponse from(TemporalRow row) {
        Objects.requireNonNull(row, "row");
        return new ObjectQueryResponse(
                row.objectType(),
                row.version().id(),
                row.version().version(),
                row.version().validFrom(),
                row.version().validTo(),
                row.version().recordedAt(),
                row.version().supersededAt(),
                row.fields());
    }
}
