package cn.byteforce.seedj.it.fixture.hr;

import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventSubscriber;
import cn.byteforce.seedj.eventbus.EventSubscription;
import org.springframework.stereotype.Component;

/**
 * 仅测试用的 HR 消费者：永远失败，用来验证 coord 消费账本的「重试 → 超过上限 → 死信」链路。
 *
 * <p>失败类型是可重试的 {@link IllegalStateException}；容器按退避重投，达到 {@code
 * seedj.eventbus.consumer.max-attempts} 后写入死信。
 */
@Component
public class HrFailingSubscriber implements EventSubscriber<HrOrderPlaced> {

    static final String GROUP = "hr-failing";
    static final String TOPIC = "it.failure";

    @Override
    public EventSubscription subscription() {
        return EventSubscription.of(GROUP, TOPIC);
    }

    @Override
    public void onEvent(EventEnvelope<HrOrderPlaced> envelope) {
        throw new IllegalStateException("it-failure-" + envelope.event().orderId());
    }
}
