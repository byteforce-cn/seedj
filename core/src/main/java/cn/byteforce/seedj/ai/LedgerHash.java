package cn.byteforce.seedj.ai;

import cn.byteforce.seedj.core.utils.CanonicalJsonHelper;
import cn.byteforce.seedj.core.utils.HashesHelper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 账本记录的哈希算法：{@code record_hash} 的唯一实现。
 *
 * <p>哈希前像是本条记录的规范 JSON（键排序、无空白），字段为 {@code record_id}、{@code decision_id}、 {@code record_kind}、
 * {@code partition}、{@code sequence}、{@code prev_hash}、{@code recorded_at}、{@code tenant_id}、
 * {@code body}——覆盖除 {@code record_hash} 自身以外的全部记录内容。覆盖率的意义：改序号、改时间、改记录体都会换掉哈希， 序列号被篡改无法躲过 {@code
 * verify}。
 *
 * <p>{@code recorded_at} 序列化为 {@link java.time.Instant#toString()}（UTC，保留调用方写入的值）， {@code
 * record_kind} 用小写线格式名，{@code body} 是嵌套对象。内存实现与 PostgreSQL 实现共用本类， 保证两套账本对同一记录给出同一个哈希。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class LedgerHash {

    /** 分区首条记录的 {@code prev_hash}：64 个零。 */
    public static final String GENESIS = "0".repeat(64);

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private LedgerHash() {}

    /**
     * 计算一条未定哈希的记录的 {@code record_hash}。
     *
     * <p>入参的 {@code recordHash} 被忽略：调用方用任意合法的 64 位十六进制占位即可（通常取 {@link #GENESIS}）。
     *
     * @param unsigned 除 {@code recordHash} 外字段齐全的记录
     * @return 64 位小写十六进制哈希
     * @throws NullPointerException {@code unsigned} 为 {@code null}
     */
    public static String of(DecisionRecord unsigned) {
        Objects.requireNonNull(unsigned, "unsigned");
        return HashesHelper.sha256Hex(CanonicalJsonHelper.canonicalize(preimage(unsigned)));
    }

    /** 哈希前像：记录的规范 JSON 去掉 {@code record_hash} 自身。 */
    private static ObjectNode preimage(DecisionRecord record) {
        ObjectNode node = NODES.objectNode();
        node.put("record_id", record.recordId().toString());
        node.put("decision_id", record.decisionId().toString());
        node.put("record_kind", record.kind().wire());
        node.put("partition", record.partition());
        node.put("sequence", record.sequence());
        node.put("prev_hash", record.prevHash());
        node.put("recorded_at", record.recordedAt().toString());
        node.put("tenant_id", record.tenantId().toString());
        node.set("body", value(record.body()));
        return node;
    }

    /** 把账本记录体的 Java 值换成 JSON 节点；null 值保留为 {@code null} 字面量。 */
    private static JsonNode value(Object raw) {
        if (raw == null) {
            return NODES.nullNode();
        }
        if (raw instanceof JsonNode node) {
            return node;
        }
        if (raw instanceof String text) {
            return NODES.textNode(text);
        }
        if (raw instanceof Boolean flag) {
            return NODES.booleanNode(flag);
        }
        if (raw instanceof BigDecimal decimal) {
            return NODES.numberNode(decimal);
        }
        if (raw instanceof BigInteger integer) {
            return NODES.numberNode(integer);
        }
        if (raw instanceof Double || raw instanceof Float) {
            return NODES.numberNode(((Number) raw).doubleValue());
        }
        if (raw instanceof Number number) {
            return NODES.numberNode(number.longValue());
        }
        if (raw instanceof Map<?, ?> map) {
            ObjectNode object = NODES.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.set(String.valueOf(entry.getKey()), value(entry.getValue()));
            }
            return object;
        }
        if (raw instanceof Iterable<?> items) {
            ArrayNode array = NODES.arrayNode();
            for (Object item : items) {
                array.add(value(item));
            }
            return array;
        }
        return NODES.textNode(raw.toString());
    }
}
