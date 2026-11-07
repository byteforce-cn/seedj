package cn.byteforce.seedj.it.fixture.hr;

import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventSubscriber;
import cn.byteforce.seedj.eventbus.EventSubscription;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 仅测试用的 HR 消费者：验证跨服务 subscribe / 消费组 / ack 全链路。 */
@Component
public class HrEventSubscriber implements EventSubscriber<HrOrderPlaced> {

    private static final Map<String, Integer> CONSUMED = new ConcurrentHashMap<>();

    @Override
    public EventSubscription subscription() {
        return EventSubscription.of("hr-consumers", "it.consume");
    }

    @Override
    public void onEvent(EventEnvelope<HrOrderPlaced> envelope) {
        CONSUMED.merge(envelope.event().orderId(), 1, Integer::sum);
    }

    public static Map<String, Integer> consumed() {
        return Map.copyOf(CONSUMED);
    }

    public static void clear() {
        CONSUMED.clear();
    }
}
