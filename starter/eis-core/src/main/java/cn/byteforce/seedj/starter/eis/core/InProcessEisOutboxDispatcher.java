package cn.byteforce.seedj.starter.eis.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内 outbox 分发器（v1 缺省）：仅记录日志；
 * 跨服务通道（coord MQ relay）在首个消费者落地时替换本 Bean（见
 * {@code eis/docs/LANDING-IAM.md §3.4.2}）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class InProcessEisOutboxDispatcher implements EisOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InProcessEisOutboxDispatcher.class);

    @Override
    public void dispatch(EisOutboxEntry entry) {
        log.debug("进程内 outbox 分发: id={}, topic={}, event_id={}",
                entry.getId(), entry.getTopic(), entry.getEventId());
    }
}
