package cn.byteforce.seedj.starter.eis.domain.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * upcaster 注册表：链拼接、缺步、重复与非法步骤。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EventUpcasterRegistryTest {

    @Test
    @DisplayName("按版本顺序拼出链")
    void buildsChainInOrder() {
        EventUpcasterRegistry registry = new EventUpcasterRegistry();
        registry.register(EventUpcaster.of("a.b", 1, 2, payload -> {
            payload.put("step1", true);
            return payload;
        }));
        registry.register(EventUpcaster.of("a.b", 2, 3, payload -> {
            payload.put("step2", true);
            return payload;
        }));

        List<EventUpcaster> chain = registry.chainFor("a.b", 1, 3);

        assertThat(chain).extracting(EventUpcaster::fromVersion).containsExactly(1, 2);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        chain.forEach(step -> step.upcast(payload));
        assertThat(payload).containsEntry("step1", true).containsEntry("step2", true);
    }

    @Test
    @DisplayName("同版本不需要演进：链为空")
    void emptyChainWhenUpToDate() {
        assertThat(new EventUpcasterRegistry().chainFor("a.b", 3, 3)).isEmpty();
    }

    @Test
    @DisplayName("缺步：报错里点名缺哪一步")
    void reportsMissingStep() {
        EventUpcasterRegistry registry = new EventUpcasterRegistry();
        registry.register(EventUpcaster.of("a.b", 1, 2, payload -> payload));

        assertThatThrownBy(() -> registry.chainFor("a.b", 1, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing upcaster for \"a.b\" v2 -> v3")
                .hasMessageContaining("EventUpcaster.of(\"a.b\", 2, 3");
    }

    @Test
    @DisplayName("启动期缺步清单")
    void missingSteps() {
        EventUpcasterRegistry registry = new EventUpcasterRegistry();
        registry.register(EventUpcaster.of("a.b", 1, 2, payload -> payload));

        assertThat(registry.missingSteps("a.b", 4)).containsExactly("v2 -> v3", "v3 -> v4");
        assertThat(registry.missingSteps("a.b", 2)).isEmpty();
    }

    @Test
    @DisplayName("重复注册同一源版本：立即失败")
    void rejectsDuplicateStep() {
        EventUpcasterRegistry registry = new EventUpcasterRegistry();
        registry.register(EventUpcaster.of("a.b", 1, 2, payload -> payload));

        assertThatThrownBy(() -> registry.register(EventUpcaster.of("a.b", 1, 2, payload -> payload)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate upcaster");
    }

    @Test
    @DisplayName("跨版本与非法源版本被拒绝")
    void rejectsIllegalSteps() {
        assertThatThrownBy(() -> EventUpcaster.of("a.b", 1, 3, payload -> payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one version");
        assertThatThrownBy(() -> EventUpcaster.of(" ", 1, 2, payload -> payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType must not be blank");
        assertThatThrownBy(() -> EventUpcaster.of("a.b", 0, 1, payload -> payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromVersion must be >= 1");
    }

    @Test
    @DisplayName("已注册事件集合与步骤快照")
    void exposesRegisteredTypes() {
        EventUpcasterRegistry registry = new EventUpcasterRegistry();
        registry.register(EventUpcaster.of("a.b", 1, 2, payload -> payload));
        registry.register(EventUpcaster.of("c.d", 1, 2, payload -> payload));

        assertThat(registry.eventTypes()).containsExactlyInAnyOrder("a.b", "c.d");
        assertThat(registry.stepsOf("a.b")).containsOnlyKeys(1);
        assertThat(registry.stepsOf("missing")).isEmpty();
    }
}
