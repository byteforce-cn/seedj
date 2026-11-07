package cn.byteforce.seedj.it.fixture.hr;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HR 测试端点：暴露消费组已处理的事件，供跨服务 IT 断言。 */
@RestController
@RequestMapping("/api/public/test/events")
public class HrTestController {

    @GetMapping("/consumed")
    public Map<String, Object> consumed() {
        return Map.of("consumed", HrEventSubscriber.consumed());
    }
}
