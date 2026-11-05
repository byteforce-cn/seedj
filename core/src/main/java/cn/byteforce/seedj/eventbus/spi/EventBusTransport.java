package cn.byteforce.seedj.eventbus.spi;

import cn.byteforce.seedj.eventbus.EventMessage;

import java.util.concurrent.CompletionStage;

/**
 * 事件传输的<b>机制</b>契约：把一条消息送到本地队列（内存 / 磁盘 journal）。
 *
 * <p>实现必须：
 * <ul>
 *   <li>线程安全，可并发调用；</li>
 *   <li>失败时返回 exceptionally completed stage 或抛
 *       {@link cn.byteforce.seedj.eventbus.EventBusException}，不得静默丢弃；</li>
 *   <li>不修改传入消息；</li>
 *   <li>{@code close()} 幂等且不抛。</li>
 * </ul>
 */
public interface EventBusTransport extends AutoCloseable {

    CompletionStage<Void> send(EventMessage message);

    /**
     * 确保 topic 存在（目录、MQ topic 等）。实现必须幂等；默认无操作。
     */
    default void ensureTopic(String topic) {
    }

    /**
     * topic 是否已存在。默认认为无需管理，始终返回 {@code true}。
     */
    default boolean topicExists(String topic) {
        return true;
    }

    @Override
    default void close() {
    }
}
