package cn.byteforce.seedj.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次追加请求（意图级）：调用方只给业务时点、操作者与领域字段，
 * 系统时间、版本号与创建/更新字段由 {@link Sealing} 计算。
 *
 * @param objectType 对象类型
 * @param id         对象 ID
 * @param validFrom  业务生效时间
 * @param validTo    业务失效时间，{@code null} 表示当前有效
 * @param actor      操作者
 * @param fields     领域字段
 * @param kind       追加类型
 * @author ByteForce
 * @since 1.0.0
 */
public record AppendRequest(String objectType, UUID id, Instant validFrom, Instant validTo,
                            UUID actor, Map<String, Object> fields, AppendKind kind) {

    public AppendRequest {
        if (objectType == null || objectType.isBlank()) {
            throw new IllegalArgumentException("objectType must not be blank");
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(kind, "kind");
        validFrom = validFrom.truncatedTo(ChronoUnit.MICROS);
        validTo = validTo == null ? null : validTo.truncatedTo(ChronoUnit.MICROS);
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /**
     * 创建（对象不得已存在）。
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     * @param validFrom  业务生效时间
     * @param validTo    业务失效时间，可为 {@code null}
     * @param actor      操作者
     * @param fields     领域字段
     * @return 追加请求
     */
    public static AppendRequest create(String objectType, UUID id, Instant validFrom,
                                       Instant validTo, UUID actor, Map<String, Object> fields) {
        return new AppendRequest(objectType, id, validFrom, validTo, actor, fields,
                AppendKind.CREATE);
    }

    /**
     * 常规更正（与 {@link #create} 同语义，命名表达意图）。
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     * @param validFrom  业务生效时间
     * @param validTo    业务失效时间，可为 {@code null}
     * @param actor      操作者
     * @param fields     领域字段
     * @return 追加请求
     */
    public static AppendRequest correction(String objectType, UUID id, Instant validFrom,
                                           Instant validTo, UUID actor, Map<String, Object> fields) {
        return new AppendRequest(objectType, id, validFrom, validTo, actor, fields,
                AppendKind.CORRECTION);
    }

    /**
     * 迟到事实追加。
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     * @param validFrom  业务生效时间，必须早于当前系统时间
     * @param validTo    业务失效时间，可为 {@code null}
     * @param actor      操作者
     * @param fields     领域字段
     * @return 追加请求
     */
    public static AppendRequest lateFact(String objectType, UUID id, Instant validFrom,
                                         Instant validTo, UUID actor, Map<String, Object> fields) {
        return new AppendRequest(objectType, id, validFrom, validTo, actor, fields,
                AppendKind.LATE_FACT);
    }
}
