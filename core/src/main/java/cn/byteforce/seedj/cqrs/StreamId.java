package cn.byteforce.seedj.cqrs;

import java.util.Objects;

/**
 * 事件流的标识：<b>聚合类型 + 聚合标识</b>。
 *
 * <p>类型是流标识的一部分，不是装饰：{@code Order(1)} 与 {@code Invoice(1)} 是两条
 * 独立的流，各自持有版本、并发控制与投影进度。只用裸标识做流键，两类聚会写进同一条流， 等到数据错乱才会暴露。
 *
 * @param <ID> 聚合标识类型
 * @param aggregateType 聚合的稳定逻辑名；类改名后必须保持不变
 * @param id 聚合标识
 * @author ByteForce
 * @since 1.0.0
 */
public record StreamId<ID>(String aggregateType, ID id) {

    public StreamId {
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new CqrsException(
                    CqrsErrorCode.INVALID_STREAM_ID, "aggregateType must not be blank");
        }
        if (aggregateType.indexOf(':') >= 0) {
            // canonical() 按第一个 ':' 切分类型与标识，类型里出现 ':' 会让流键不可解析
            throw new CqrsException(
                    CqrsErrorCode.INVALID_STREAM_ID,
                    "aggregateType must not contain ':': " + aggregateType);
        }
        Objects.requireNonNull(id, "id");
    }

    /** 简写工厂。 */
    public static <ID> StreamId<ID> of(String aggregateType, ID id) {
        return new StreamId<>(aggregateType, id);
    }

    /**
     * 存储/跨进程使用的规范文本：{@code aggregateType:encodedId}。
     *
     * <p>解析时按第一个 {@code ':'} 切分即可，因为 {@code aggregateType} 不包含它。
     */
    public String canonical(IdCodec<ID> codec) {
        Objects.requireNonNull(codec, "codec");
        return aggregateType + ':' + codec.encode(id);
    }

    /** 仅用于日志与异常信息；跨进程请用 {@link #canonical(IdCodec)}。 */
    @Override
    public String toString() {
        return aggregateType + '#' + id;
    }
}
