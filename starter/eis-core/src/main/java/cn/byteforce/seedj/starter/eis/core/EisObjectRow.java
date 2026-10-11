package cn.byteforce.seedj.starter.eis.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 对象版本行：双时间轴元字段 + 领域数据（{@code core.ddl} 对象表模板的 Java 视图）。
 *
 * <p>半开区间语义（T-R002）：业务时间 {@code [valid_from, valid_to)}、
 * 系统时间 {@code [recorded_at, superseded_at)}；{@code superseded_at == null}
 * 即当前记录。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisObjectRow {

    private final String objectType;

    private final UUID id;

    private final long version;

    private final Instant validFrom;

    private final Instant validTo;

    private final Instant recordedAt;

    private final Instant supersededAt;

    private final UUID createdBy;

    private final Instant createdAt;

    private final UUID updatedBy;

    private final Instant updatedAt;

    private final Map<String, Object> data;

    /**
     * 构造对象行。
     *
     * @param objectType   逻辑类型名
     * @param id           对象 ID
     * @param version      版本号
     * @param validFrom    业务生效时间
     * @param validTo      业务失效时间（可空）
     * @param recordedAt   系统记录时间
     * @param supersededAt 系统替代时间（可空 = 当前）
     * @param createdBy    创建者
     * @param createdAt    创建时间
     * @param updatedBy    更新者（可空）
     * @param updatedAt    更新时间（可空）
     * @param data         领域数据（不含敏感列）
     */
    public EisObjectRow(String objectType, UUID id, long version, Instant validFrom, Instant validTo,
                        Instant recordedAt, Instant supersededAt, UUID createdBy, Instant createdAt,
                        UUID updatedBy, Instant updatedAt, Map<String, Object> data) {
        this.objectType = objectType;
        this.id = id;
        this.version = version;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.recordedAt = recordedAt;
        this.supersededAt = supersededAt;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
        this.data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /**
     * 获取类型名。
     *
     * @return 对象类型
     */
    public String getObjectType() {
        return objectType;
    }

    /**
     * 获取对象 ID。
     *
     * @return 对象 ID
     */
    public UUID getId() {
        return id;
    }

    /**
     * 获取版本号。
     *
     * @return 版本号
     */
    public long getVersion() {
        return version;
    }

    /**
     * 获取业务生效时间。
     *
     * @return valid_from
     */
    public Instant getValidFrom() {
        return validFrom;
    }

    /**
     * 获取业务失效时间。
     *
     * @return valid_to；当前有效为 {@code null}
     */
    public Instant getValidTo() {
        return validTo;
    }

    /**
     * 获取系统记录时间。
     *
     * @return recorded_at
     */
    public Instant getRecordedAt() {
        return recordedAt;
    }

    /**
     * 获取系统替代时间。
     *
     * @return superseded_at；当前记录为 {@code null}
     */
    public Instant getSupersededAt() {
        return supersededAt;
    }

    /**
     * 获取创建者。
     *
     * @return 创建者 UUID
     */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /**
     * 获取创建时间。
     *
     * @return created_at
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取更新者。
     *
     * @return 更新者 UUID；可空
     */
    public UUID getUpdatedBy() {
        return updatedBy;
    }

    /**
     * 获取更新时间。
     *
     * @return updated_at；可空
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 获取领域数据。
     *
     * @return 领域数据映射（只读）
     */
    public Map<String, Object> getData() {
        return data;
    }

    /**
     * 按名取领域字段（含未注册键的透传能力，读取方应使用注册表校验）。
     *
     * @param name 字段名
     * @return 字段值；无则为 {@code null}
     */
    public Object field(String name) {
        return data.get(name);
    }
}
