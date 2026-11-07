/**
 * Outbox 的机制契约（provider 与 relay 使用）。
 *
 * <p>业务方只需要 {@link cn.byteforce.seedj.outbox.OutboxWriter}； {@link
 * cn.byteforce.seedj.outbox.spi.OutboxStore} 与 {@link cn.byteforce.seedj.outbox.spi.OutboxSink} 是
 * relay 与适配器之间的内部 seam。
 *
 * <h2>为什么拆成 store 与 sink</h2>
 *
 * claim / lease / heartbeat / attempts 是 relay 的协调机制，不是投递语义； destination
 * 如何解释是接入方（eventbus、webhook）的事。两者分开后， outbox 可以脱离任何具体消息系统存在。
 */
package cn.byteforce.seedj.outbox.spi;
