package cn.byteforce.seedj.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一条双时间轴对象行：元结构 + 领域字段。
 *
 * <p>领域字段保持插入顺序；存储实现按对象表注册的列名读写，未注册的键忽略。
 *
 * @param objectType 对象类型（对应一张对象表）
 * @param version 元结构
 * @param fields 领域字段（不可变、保持顺序）
 * @author ByteForce
 * @since 1.0.0
 */
public record TemporalRow(String objectType, ObjectVersion version, Map<String, Object> fields) {

    public TemporalRow {
        if (objectType == null || objectType.isBlank()) {
            throw new IllegalArgumentException("objectType must not be blank");
        }
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(fields, "fields");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
