package cn.byteforce.seedj.starter.eis.core;

/**
 * 对象字段类型：业务对象表领域列的受支持映射（与 schema-compiler 生成物对齐）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum EisFieldKind {

    /** {@code TEXT} ↔ {@link String}。 */
    TEXT,

    /** {@code BOOLEAN} ↔ {@link Boolean}。 */
    BOOLEAN,

    /** {@code UUID} ↔ {@link java.util.UUID}。 */
    UUID,

    /** {@code TIMESTAMPTZ(6)} ↔ {@link java.time.Instant}。 */
    TIMESTAMP,

    /** {@code JSONB} ↔ {@link tools.jackson.databind.JsonNode}。 */
    JSONB,

    /** {@code TEXT[]} ↔ {@link java.util.List List&lt;String&gt;}。 */
    TEXT_ARRAY
}
