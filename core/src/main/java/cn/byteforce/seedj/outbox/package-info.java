/**
 * Outbox 的业务契约（生产者使用）。
 *
 * <p>业务方只需要 {@link cn.byteforce.seedj.outbox.OutboxWriter} 与
 * {@link cn.byteforce.seedj.outbox.OutboxRecord}；
 * {@link cn.byteforce.seedj.outbox.spi.OutboxStore} 与
 * {@link cn.byteforce.seedj.outbox.spi.OutboxSink} 是 relay 与适配器之间的内部 seam。
 *
 * <h2>为什么 outbox 是独立能力</h2>
 * outbox 表达的是"事务性写入 + 可靠投递"，不依赖任何事件模型。它不知道
 * DomainEvent、topic、消费者组；payload 是不透明字节。eventbus 只是它的
 * 第一个投递适配器。
 *
 * <h2>新增一个 outbox 适配器需要做什么</h2>
 * <ol>
 *   <li>实现 {@code OutboxStore}（存储）或 {@code OutboxSink}（投递）；</li>
 *   <li>在实现模块里提供 {@code @AutoConfiguration} + bean，并声明
 *       {@code @ConditionalOnMissingBean}；</li>
 *   <li>用 {@code seedj.outbox.provider} 选择实现；</li>
 *   <li>把 {@code core} 的 {@code OutboxStoreContractTest} {@code extends} 一行并跑通；</li>
 *   <li>在 {@code docs/spi/README.md} 的适配器表里加一行。</li>
 * </ol>
 */
package cn.byteforce.seedj.outbox;
