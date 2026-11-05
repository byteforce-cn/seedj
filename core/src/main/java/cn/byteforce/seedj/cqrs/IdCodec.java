package cn.byteforce.seedj.cqrs;

/**
 * 标识与规范文本之间的双向编解码。
 *
 * <p>这是全系统<b>唯一</b>的标识转换点：事件存储的流键、投影表主键、跨语言消息的
 * 关联字段都从这里取文本形态。业务适配器不得各自写 {@code String.valueOf}，否则
 * 同一个标识在不同存储里会有不同形态，幂等与关联会静默失效。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>{@link #encode} 必须<b>单射</b>：不同标识必须得到不同文本；</li>
 *   <li>{@link #encode} 必须<b>稳定</b>：同一标识任何时候都得到同一文本；</li>
 *   <li>{@code decode(encode(id))} 必须与 {@code id} 相等；</li>
 *   <li>非法文本抛 {@link CqrsException}（{@code INVALID_ID}），不得返回 {@code null}
 *       或静默降级。</li>
 * </ul>
 *
 * <p><b>不要</b>让文本形态参与大小比较：雪花字符串不补零，字典序与数值序不一致。
 * 需要顺序时用存储分配的 {@code sequence} 或原生数值列。
 *
 * @param <ID> 标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface IdCodec<ID> {

    /**
     * 标识 → 规范文本。
     *
     * @param id 非空标识
     * @return 非空文本
     */
    String encode(ID id);

    /**
     * 规范文本 → 标识。
     *
     * @param encoded 非空文本
     * @return 标识
     * @throws CqrsException 文本不合法
     */
    ID decode(String encoded);
}
