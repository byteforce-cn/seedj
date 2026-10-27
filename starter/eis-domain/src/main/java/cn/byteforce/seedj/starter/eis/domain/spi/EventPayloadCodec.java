package cn.byteforce.seedj.starter.eis.domain.spi;

import cn.byteforce.seedj.starter.eis.domain.DomainEvent;

/**
 * 事件 payload 与事件对象之间的唯一出入口。
 *
 * <p>「写库」与「读库」都必须经过这里，否则会出现两条读路径：一条做 upcasting、
 * 一条不做——读模型会拿到三年前的形状。</p>
 *
 * <p>实现必须保证：</p>
 * <ul>
 *   <li>写入按稳定逻辑名与 {@code schema_version} 落库；</li>
 *   <li>读取时旧版本经 {@link EventUpcaster} 链补齐；</li>
 *   <li>读到比代码更新的数据（回滚部署 / 连错库）<b>必须失败</b>，不允许按当前类静默读取；</li>
 *   <li>缺 upcaster 时抛出带缺失步骤的异常，不返回 {@code null}。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EventPayloadCodec {

    /**
     * 把事件序列化为 payload 文本。
     *
     * @param event 事件
     * @return payload 文本
     */
    String encode(DomainEvent event);

    /**
     * 把 payload 反序列化为当前代码中的事件对象，必要时先做 upcasting。
     *
     * @param eventType     稳定逻辑名（{@code evt_log.event_type}）
     * @param schemaVersion 写入时的版本（{@code evt_log.schema_version}）
     * @param payload       原始 payload 文本
     * @return 事件对象
     * @throws IllegalStateException 存储版本超前、缺 upcaster 或 payload 非法
     */
    DomainEvent decode(String eventType, int schemaVersion, String payload);
}
