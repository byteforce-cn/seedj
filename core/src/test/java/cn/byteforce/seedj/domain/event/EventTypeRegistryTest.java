package cn.byteforce.seedj.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 事件类型注册表：逻辑名解析、schema 版本、冲突与非法声明。 */
class EventTypeRegistryTest {

    @Test
    @DisplayName("注册后可按名字解析，名字与版本取注解")
    void registerAndResolve() {
        EventTypeRegistry registry = new EventTypeRegistry();

        registry.register(AccountOpened.class);

        assertThat(registry.resolve("account.opened")).isEqualTo(AccountOpened.class);
        assertThat(registry.nameOf(AccountOpened.class)).isEqualTo("account.opened");
        assertThat(registry.versionOf(AccountOpened.class)).isEqualTo(2);
        assertThat(registry.find("account.opened")).isPresent();
        assertThat(registry.find("account.closed")).isEmpty();
        assertThat(registry.names()).containsExactly("account.opened");
    }

    @Test
    @DisplayName("未注册类型按注解即时登记；无注解回退类简单名与版本 1")
    void implicitRegistration() {
        EventTypeRegistry registry = new EventTypeRegistry();

        assertThat(registry.nameOf(AccountOpened.class)).isEqualTo("account.opened");
        assertThat(registry.resolve("account.opened")).isEqualTo(AccountOpened.class);

        assertThat(registry.nameOf(NoAnnotation.class)).isEqualTo("NoAnnotation");
        assertThat(registry.versionOf(NoAnnotation.class)).isEqualTo(1);
        assertThat(registry.resolve("NoAnnotation")).isEqualTo(NoAnnotation.class);
    }

    @Test
    @DisplayName("快照是不可修改的 名字 → 类型 视图")
    void snapshotIsImmutableCopy() {
        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register(AccountOpened.class);
        registry.register(NoAnnotation.class);

        Map<String, Class<? extends DomainEvent>> snapshot = registry.snapshot();

        assertThat(snapshot)
                .containsEntry("account.opened", AccountOpened.class)
                .containsEntry("NoAnnotation", NoAnnotation.class);
        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("同名不同类型立即失败")
    void nameCollisionFailsFast() {
        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register(AccountOpened.class);

        assertThatThrownBy(() -> registry.register(DuplicateName.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Event type name collision")
                .hasMessageContaining("account.opened");
    }

    @Test
    @DisplayName("重复注册同一类型是幂等的")
    void reRegisterSameTypeIsIdempotent() {
        EventTypeRegistry registry = new EventTypeRegistry();

        registry.register(AccountOpened.class);
        registry.register(AccountOpened.class);

        assertThat(registry.names()).containsExactly("account.opened");
    }

    @Test
    @DisplayName("未注册名字解析失败并提示已注册集合")
    void resolveUnknownFails() {
        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register(AccountOpened.class);

        assertThatThrownBy(() -> registry.resolve("account.unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type")
                .hasMessageContaining("account.opened");
    }

    @Test
    @DisplayName("非 DomainEvent 类型拒绝登记")
    void rejectsNonDomainEvent() {
        EventTypeRegistry registry = new EventTypeRegistry();

        assertThatThrownBy(() -> registry.nameOf(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not implement DomainEvent");
    }

    @Test
    @DisplayName("注解逻辑名为空白或版本小于 1 时失败")
    void rejectsIllegalAnnotation() {
        assertThatThrownBy(() -> EventTypeRegistry.logicalName(BlankName.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> EventTypeRegistry.schemaVersion(BadVersion.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version must be >= 1");
    }

    @Test
    @DisplayName("DomainEvent 默认方法取注解值")
    void domainEventDefaultsReadAnnotation() {
        DomainEvent event = new AccountOpened();

        assertThat(event.eventType()).isEqualTo("account.opened");
        assertThat(event.schemaVersion()).isEqualTo(2);
        assertThat(new NoAnnotation().eventType()).isEqualTo("NoAnnotation");
        assertThat(new NoAnnotation().schemaVersion()).isEqualTo(1);
    }

    @EventType(value = "account.opened", version = 2)
    static final class AccountOpened implements DomainEvent {}

    @EventType("account.opened")
    static final class DuplicateName implements DomainEvent {}

    static final class NoAnnotation implements DomainEvent {}

    @EventType("  ")
    static final class BlankName implements DomainEvent {}

    @EventType(value = "account.bad", version = 0)
    static final class BadVersion implements DomainEvent {}
}
