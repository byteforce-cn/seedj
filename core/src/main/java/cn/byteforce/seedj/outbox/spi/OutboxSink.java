package cn.byteforce.seedj.outbox.spi;

import cn.byteforce.seedj.outbox.OutboxRecord;

import java.util.concurrent.CompletionStage;

/**
 * Outbox 的<b>出口</b>契约：把一条已领取的记录送到真正的投递目标
 * （事件总线、webhook、外部系统）。
 *
 * <p>实现方通常是某个具体能力的适配器，例如 eventbus 的
 * {@code EventBusOutboxSink}。outbox 本身不认识 destination 的语义。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>at-least-once：同一条记录可能被重复投递，sink 侧必须按
 *       {@link OutboxRecord#messageId()} 幂等；</li>
 *   <li>失败时返回 exceptionally completed stage 或抛
 *       {@link cn.byteforce.seedj.outbox.OutboxException}，不得静默丢弃；</li>
 *   <li>不修改传入记录；</li>
 *   <li>不负责 claim / lease；生命周期由提供方管理，不由 relay 关闭。</li>
 * </ul>
 *
 * <p>一个应用只允许一个 {@code OutboxSink} bean；多 sink 路由不在当前能力范围内。
 */
public interface OutboxSink {

    /**
     * 投递一条记录。正常完成即视为成功。
     */
    CompletionStage<Void> deliver(OutboxRecord record);
}
