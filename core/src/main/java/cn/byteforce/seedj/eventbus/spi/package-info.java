/**
 * EventBus 的机制契约（transport / consumer provider 使用）。
 *
 * <p>业务方只需要 {@link cn.byteforce.seedj.eventbus.EventPublisher} 与
 * {@link cn.byteforce.seedj.eventbus.EventSubscriber}；
 * {@link cn.byteforce.seedj.eventbus.spi.EventBusTransport} 与
 * {@link cn.byteforce.seedj.eventbus.spi.EventConsumerStore} 是装配层与适配器之间的内部 seam。
 *
 * <h2>新增一个适配器需要做什么</h2>
 * <ol>
 *   <li>实现 {@code EventBusTransport} 或 {@code EventConsumerStore}；</li>
 *   <li>在实现模块里提供名为 {@code <provider>} 的 {@code @Bean}；</li>
 *   <li>把 {@code core} 的契约测试套件 {@code extends} 一行并跑通；</li>
 *   <li>在 SPI 适配器登记表里加一行（表由独立文档仓维护）。</li>
 * </ol>
 */
package cn.byteforce.seedj.eventbus.spi;
