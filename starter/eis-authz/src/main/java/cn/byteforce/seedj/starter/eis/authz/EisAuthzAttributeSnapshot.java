package cn.byteforce.seedj.starter.eis.authz;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 主体属性快照（{@code attributes.api.yaml} 的 200 响应形状；ADR-0004 拉取为准）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisAuthzAttributeSnapshot {

    private final String principal;

    private final Instant asOf;

    private final Instant recordedAt;

    private final Instant expiresAt;

    private final String source;

    private final String sourceVersion;

    private final EisAuthzAttributeState state;

    private final EisAuthzAttributeSet attributes;

    /**
     * 构造快照。
     *
     * @param principal     主体 ID
     * @param asOf          业务时点
     * @param recordedAt    系统记录时点
     * @param expiresAt     快照有效期（可空 = 无有效期声明）
     * @param source        权威属主（如 hr）
     * @param sourceVersion 属主契约版本（可空）
     * @param state         快照状态
     * @param attributes    属性集合
     */
    public EisAuthzAttributeSnapshot(String principal, Instant asOf, Instant recordedAt, Instant expiresAt,
                                     String source, String sourceVersion, EisAuthzAttributeState state,
                                     EisAuthzAttributeSet attributes) {
        this.principal = principal;
        this.asOf = asOf;
        this.recordedAt = recordedAt;
        this.expiresAt = expiresAt;
        this.source = source;
        this.sourceVersion = sourceVersion;
        this.state = state;
        this.attributes = attributes == null ? EisAuthzAttributeSet.empty() : attributes;
    }

    /**
     * 从契约 JSON 解析（{@code attributes.api.yaml} 200 响应）。
     *
     * @param node 快照 JSON
     * @return 快照
     * @throws IllegalArgumentException 形状非法
     */
    public static EisAuthzAttributeSnapshot fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("属性快照必须为 JSON 对象");
        }
        return new EisAuthzAttributeSnapshot(text(node, "principal"), instant(node, "as_of"),
                instant(node, "recorded_at"), instant(node, "expires_at"), text(node, "source"),
                text(node, "source_version"), EisAuthzAttributeState.fromCode(text(node, "attribute_state")),
                EisAuthzAttributeSet.fromJson(node.get("snapshot")));
    }

    /**
     * 从判定请求的 {@code AttributeContext} 构造（{@code resolution.api.yaml} 可选字段；
     * 调用方已按 ATR-002 拉取快照时不再回源）。
     *
     * @param principal     主体 ID
     * @param asOf          业务时点
     * @param recordedAsOf  系统时点（可空）
     * @param context       AttributeContext JSON
     * @return 快照；{@code context} 为空（null/非对象）时为 {@code null}
     */
    public static EisAuthzAttributeSnapshot fromAttributeContext(String principal, Instant asOf,
                                                                 Instant recordedAsOf, JsonNode context) {
        if (context == null || !context.isObject()) {
            return null;
        }
        JsonNode snapshotNode = context.get("snapshot");
        EisAuthzAttributeState state = EisAuthzAttributeState.fromCode(text(context, "attribute_state"));
        if (state == null) {
            state = snapshotNode != null && snapshotNode.isObject()
                    ? EisAuthzAttributeState.FRESH : EisAuthzAttributeState.NOT_REQUIRED;
        }
        return new EisAuthzAttributeSnapshot(principal, asOf, recordedAsOf, null, text(context, "source"),
                null, state, EisAuthzAttributeSet.fromJson(snapshotNode));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }

    /**
     * 获取主体 ID。
     *
     * @return 主体 ID
     */
    public String getPrincipal() {
        return principal;
    }

    /**
     * 获取业务时点。
     *
     * @return 业务时点
     */
    public Instant getAsOf() {
        return asOf;
    }

    /**
     * 获取系统记录时点。
     *
     * @return 系统记录时点
     */
    public Instant getRecordedAt() {
        return recordedAt;
    }

    /**
     * 获取有效期。
     *
     * @return 有效期；可空
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * 获取权威属主。
     *
     * @return 属主标识；可空
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取属主契约版本。
     *
     * @return 版本；可空
     */
    public String getSourceVersion() {
        return sourceVersion;
    }

    /**
     * 获取快照状态。
     *
     * @return 状态；未声明为 {@code null}
     */
    public EisAuthzAttributeState getState() {
        return state;
    }

    /**
     * 获取属性集合。
     *
     * @return 属性集合（非空）
     */
    public EisAuthzAttributeSet getAttributes() {
        return attributes;
    }
}
