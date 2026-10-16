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
    TEXT_ARRAY,

    /**
     * {@code UUID[]} ↔ {@link java.util.List List&lt;UUID&gt;}
     * （{@code type: array, items.format: uuid} 生成物）。
     *
     * <p>写入接受 {@code List<UUID>}（元素为字符串 UUID 时按
     * {@link java.util.UUID#fromString(String)} 归一，非法值报错），
     * 读取返回不可变 {@code List<UUID>}，NULL 列为空列表。</p>
     */
    UUID_ARRAY,

    /** {@code DATE} ↔ {@link java.time.LocalDate}。 */
    DATE,

    /**
     * {@code BIGINT} ↔ {@link Long}（{@code type: integer} 生成物）。
     *
     * <p>写入接受任意整型 {@link Number}（如命令载荷的 {@code Integer}），读取统一返回 {@code Long}。</p>
     */
    BIGINT,

    /**
     * {@code NUMERIC} / {@code NUMERIC(p,s)} ↔ {@link java.math.BigDecimal}
     * （{@code type: number} 与金额/数量 pattern 生成物）。
     */
    NUMERIC
}
