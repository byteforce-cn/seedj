package cn.byteforce.seedj.starter.eis.domain;

import cn.byteforce.seedj.starter.eis.domain.memory.InMemoryEventLogStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 事件流仓储：装载、保存、no-op 与冲突传播。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EventSourcedRepositoryTest {

    private final InMemoryEventLogStore store = new InMemoryEventLogStore();

    private final EventSourcedRepository<TestAccount, Long> repository =
            new EventSourcedRepository<>(store, TestAccount::rehydrate);

    @Test
    @DisplayName("保存后重载：状态与版本一致")
    void saveThenLoadRoundTrip() {
        TestAccount account = TestAccount.open(1L, "alice");
        account.deposit(100L);

        repository.save(account);
        TestAccount reloaded = repository.loadOrThrow(1L);

        assertThat(reloaded.owner()).isEqualTo("alice");
        assertThat(reloaded.balance()).isEqualTo(100L);
        assertThat(reloaded.version()).isEqualTo(2L);
        assertThat(reloaded.hasUncommittedEvents()).isFalse();
        assertThat(account.hasUncommittedEvents()).isFalse();
    }

    @Test
    @DisplayName("不存在的聚合：load 为空，loadOrThrow 抛领域异常")
    void missingAggregate() {
        assertThat(repository.load(404L)).isEmpty();

        assertThatThrownBy(() -> repository.loadOrThrow(404L))
                .isInstanceOf(AggregateNotFoundException.class)
                .hasMessageContaining("Aggregate not found")
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("没有未提交事件时保存为 no-op")
    void saveWithoutEventsIsNoop() {
        TestAccount account = TestAccount.rehydrate(2L, 3L, List.of(new AccountEvent.Opened("bob")));

        repository.save(account);

        assertThat(store.loadStream("2").currentVersion()).isZero();
    }

    @Test
    @DisplayName("连续保存：版本递增，历史可重放")
    void sequentialSaves() {
        TestAccount account = TestAccount.open(3L, "carol");
        repository.save(account);

        account.deposit(10L);
        repository.save(account);

        TestAccount reloaded = repository.loadOrThrow(3L);
        assertThat(reloaded.balance()).isEqualTo(10L);
        assertThat(reloaded.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("版本冲突：异常上抛，聚合仍保留未提交事件")
    void conflictKeepsUncommittedEvents() {
        TestAccount left = TestAccount.open(4L, "dave");
        repository.save(left);

        TestAccount stale = repository.loadOrThrow(4L);
        TestAccount fresh = repository.loadOrThrow(4L);
        stale.deposit(1L);
        fresh.deposit(2L);
        repository.save(fresh);

        Throwable thrown = catchThrowable(() -> repository.save(stale));

        assertThat(thrown).isInstanceOf(OptimisticLockingException.class);
        OptimisticLockingException conflict = (OptimisticLockingException) thrown;
        assertThat(conflict.getExpectedVersion()).isEqualTo(1L);
        assertThat(conflict.getActualVersion()).isEqualTo(2L);
        assertThat(stale.hasUncommittedEvents()).isTrue();
    }

    @Test
    @DisplayName("loadOrThrow 的异常码与 ResponseCode 语义一致")
    void notFoundCode() {
        assertThat(AggregateNotFoundException.CODE).isEqualTo(10004);
        assertThat(OptimisticLockingException.CODE).isEqualTo(10008);
    }
}
