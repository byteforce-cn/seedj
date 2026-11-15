package cn.byteforce.seedj.it.fixture.hr;

import cn.byteforce.seedj.eventbus.spi.DeadLetter;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HR 测试端点：暴露消费组已处理的事件与死信，供跨服务 IT 断言。 */
@RestController
@RequestMapping("/api/public/test/events")
public class HrTestController {

    private final ObjectProvider<EventConsumerStore> stores;

    public HrTestController(ObjectProvider<EventConsumerStore> stores) {
        this.stores = stores;
    }

    @GetMapping("/consumed")
    public Map<String, Object> consumed() {
        return Map.of("consumed", HrEventSubscriber.consumed());
    }

    /** 读失败订阅的死信；没有 consumer store 时返回空列表。 */
    @GetMapping("/dead-letters")
    public Map<String, Object> deadLetters() {
        EventConsumerStore store = stores.getIfAvailable();
        List<Map<String, Object>> letters =
                store == null
                        ? List.of()
                        : store
                                .deadLetters(
                                        HrFailingSubscriber.TOPIC, HrFailingSubscriber.GROUP, 20)
                                .stream()
                                .map(HrTestController::letter)
                                .toList();
        return Map.of("deadLetters", letters);
    }

    private static Map<String, Object> letter(DeadLetter letter) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("type", letter.failure().errorType());
        failure.put("message", letter.failure().errorMessage());
        failure.put("retryable", letter.failure().retryable());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", letter.id());
        result.put("topic", letter.topic());
        result.put("group", letter.group());
        result.put("eventId", letter.message().eventId());
        result.put("payload", new String(letter.message().payload(), StandardCharsets.UTF_8));
        result.put("failure", failure);
        result.put("deadLetteredAt", letter.deadLetteredAt().toString());
        return result;
    }
}
