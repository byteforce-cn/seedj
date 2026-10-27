package cn.byteforce.seedj.starter.eis.domain.internal;

import cn.byteforce.seedj.starter.eis.domain.DomainEvent;
import cn.byteforce.seedj.starter.eis.domain.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事件类型注册表：逻辑名、版本、冲突与未注册报错。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EventTypeRegistryTest {

    @Test
    @DisplayName("逻辑名与版本取自 @EventType")
    void readsAnnotation() {
        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register(Annotated.class);

        assertThat(registry.nameOf(Annotated.class)).isEqualTo("test.annotated");
        assertThat(registry.versionOf(Annotated.class)).isEqualTo(2);
        assertThat(registry.resolve("test.annotated")).isEqualTo(Annotated.class);
        assertThat(registry.find("test.annotated")).isPresent();
        assertThat(registry.names()).containsExactly("test.annotated");
        assertThat(registry.snapshot()).containsEntry("test.annotated", Annotated.class);
    }

    @Test
    @DisplayName("未标注 @EventType 时回退类简单名、版本 1")
    void fallsBackToSimpleName() {
        assertThat(EventTypeRegistry.logicalName(Unannotated.class)).isEqualTo("Unannotated");
        assertThat(EventTypeRegistry.schemaVersion(Unannotated.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("注解值为空白 / 版本小于 1：立即失败")
    void rejectsIllegalAnnotation() {
        assertThatThrownBy(() -> EventTypeRegistry.logicalName(BlankName.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be blank");

        assertThatThrownBy(() -> EventTypeRegistry.schemaVersion(ZeroVersion.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version must be >= 1");
    }

    @Test
    @DisplayName("同名不同类：注册冲突立即失败")
    void rejectsNameCollision() {
        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register(Annotated.class);

        assertThatThrownBy(() -> registry.register(AnnotatedTwin.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name collision")
                .hasMessageContaining("test.annotated");
    }

    @Test
    @DisplayName("未注册逻辑名：报错里列出已注册集合")
    void unknownNameListsRegistered() {
        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register(Annotated.class);

        assertThatThrownBy(() -> registry.resolve("nope.missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type")
                .hasMessageContaining("test.annotated");
    }

    @Test
    @DisplayName("非事件类型被拒绝")
    void rejectsNonEventType() {
        EventTypeRegistry registry = new EventTypeRegistry();

        assertThatThrownBy(() -> registry.nameOf(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not implement DomainEvent");
    }

    // ===================== 测试夹具 =====================

    /**
     * 带注解的事件。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    @EventType(value = "test.annotated", version = 2)
    static final class Annotated implements DomainEvent {
    }

    /**
     * 与 {@link Annotated} 逻辑名相同的事件。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    @EventType("test.annotated")
    static final class AnnotatedTwin implements DomainEvent {
    }

    /**
     * 未标注注解的事件。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    static final class Unannotated implements DomainEvent {
    }

    /**
     * 逻辑名为空白的事件。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    @EventType("  ")
    static final class BlankName implements DomainEvent {
    }

    /**
     * 版本小于 1 的事件。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    @EventType(value = "test.zero", version = 0)
    static final class ZeroVersion implements DomainEvent {
    }
}
