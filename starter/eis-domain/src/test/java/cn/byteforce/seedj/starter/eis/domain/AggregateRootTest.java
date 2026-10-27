package cn.byteforce.seedj.starter.eis.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AggregateRoot} 的版本、事件收集与分派失败面。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class AggregateRootTest {

    @Test
    @DisplayName("raise：立刻改状态、登记事件、版本自增")
    void raiseAppliesAndRecords() {
        TestAccount account = TestAccount.open(1L, "alice");

        account.deposit(100L);

        assertThat(account.owner()).isEqualTo("alice");
        assertThat(account.balance()).isEqualTo(100L);
        assertThat(account.version()).isEqualTo(2L);
        assertThat(account.expectedVersion()).isZero();
        assertThat(account.peekDomainEvents()).hasSize(2);
        assertThat(account.hasUncommittedEvents()).isTrue();
    }

    @Test
    @DisplayName("expectedVersion = 当前版本 - 未提交事件数")
    void expectedVersionExcludesUncommitted() {
        TestAccount account = TestAccount.rehydrate(1L, 5L, List.of());
        assertThat(account.version()).isEqualTo(5L);
        assertThat(account.expectedVersion()).isEqualTo(5L);

        account.deposit(10L);

        assertThat(account.version()).isEqualTo(6L);
        assertThat(account.expectedVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("clearDomainEvents：清空后 hasUncommittedEvents 为 false，版本不回退")
    void clearKeepsVersion() {
        TestAccount account = TestAccount.open(1L, "alice");
        account.clearDomainEvents();

        assertThat(account.hasUncommittedEvents()).isFalse();
        assertThat(account.peekDomainEvents()).isEmpty();
        assertThat(account.version()).isEqualTo(1L);
        assertThat(account.expectedVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("peekDomainEvents 返回不可变快照")
    void peekIsImmutable() {
        TestAccount account = TestAccount.open(1L, "alice");
        List<DomainEvent> events = account.peekDomainEvents();

        assertThatThrownBy(() -> events.add(new AccountEvent.Deposited(1L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("replay：只改状态，不登记事件、不涨版本")
    void replayDoesNotRecord() {
        TestAccount account = TestAccount.rehydrate(1L, 0L, List.of());

        account.replay(new AccountEvent.Deposited(50L));

        assertThat(account.balance()).isEqualTo(50L);
        assertThat(account.version()).isZero();
        assertThat(account.hasUncommittedEvents()).isFalse();
    }

    @Test
    @DisplayName("缺少 apply(X)：抛异常并给出修复模板")
    void missingHandlerFailsWithTemplate() {
        TestAccount account = TestAccount.open(1L, "alice");

        assertThatThrownBy(() -> account.replay(new AccountEvent.Frozen()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no apply(Frozen) method")
                .hasMessageContaining("implements EventApplier<Frozen>");
    }

    @Test
    @DisplayName("apply 中抛出的业务异常原样抛出，不被反射包装")
    void businessExceptionIsUnwrapped() {
        TestAccount account = TestAccount.open(1L, "alice");

        assertThatThrownBy(() -> account.deposit(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be > 0");
    }

    @Test
    @DisplayName("assertAllEventsHandled：未覆盖的 sealed 子类型被点名")
    void assertAllEventsHandledReportsMissing() {
        TestAccount account = TestAccount.open(1L, "alice");

        assertThatThrownBy(() -> account.assertAllEventsHandled(AccountEvent.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Frozen")
                .hasMessageContaining("Ignored");
    }

    @Test
    @DisplayName("assertAllEventsHandled：非 sealed 类型直接拒绝")
    void assertAllEventsHandledRejectsNonSealed() {
        TestAccount account = TestAccount.open(1L, "alice");

        assertThatThrownBy(() -> account.assertAllEventsHandled(AccountEvent.Deposited.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a sealed");
    }

    @Test
    @DisplayName("负版本构造被拒绝")
    void negativeVersionRejected() {
        assertThatThrownBy(() -> TestAccount.rehydrate(1L, -1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate version must be >= 0");
    }

    @Test
    @DisplayName("null 事件被拒绝")
    void nullEventRejected() {
        TestAccount account = TestAccount.open(1L, "alice");

        assertThatThrownBy(() -> account.replay(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("domain event must not be null");
    }

    @Test
    @DisplayName("实体按标识判等")
    void entityEqualsById() {
        TestAccount left = TestAccount.rehydrate(7L, 0L, List.of());
        TestAccount right = TestAccount.rehydrate(7L, 3L, List.of());

        assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
        assertThat(left).isNotEqualTo(TestAccount.rehydrate(8L, 0L, List.of()));
    }
}
