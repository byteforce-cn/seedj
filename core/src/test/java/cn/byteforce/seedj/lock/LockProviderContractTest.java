package cn.byteforce.seedj.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LockProvider} 契约测试套件。
 *
 * <p>只断言语义，不碰物理实现：同一键互斥、不同键独立、释放可重入、租约到期可重入、 非法入参显式失败。实现方只需要提供 {@link #newProvider()}。
 *
 * <p>租约到期用例使用真实时间（{@value #SHORT_TTL_MS}ms + 等待），因为真实 provider （Redis /
 * 数据库）无法伪造时钟；实现自己的时钟测试请放到各自的测试类里。
 */
public abstract class LockProviderContractTest {

    private static final long SHORT_TTL_MS = 150;

    private static final long WAIT_MS = 400;

    /** 每个用例一个空 provider。 */
    protected abstract LockProvider newProvider();

    private LockProvider provider;

    @BeforeEach
    void setUp() {
        provider = newProvider();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    @DisplayName("空闲键获取成功，租约带上原始键")
    void acquiresFreeKey() {
        Optional<LockLease> lease = provider.tryAcquire("order:1", Duration.ofSeconds(30));

        assertThat(lease).isPresent();
        assertThat(lease.orElseThrow().key()).isEqualTo("order:1");
    }

    @Test
    @DisplayName("同一键第二次获取失败")
    void secondAcquireOnSameKeyFails() {
        provider.tryAcquire("order:1", Duration.ofSeconds(30)).orElseThrow();

        assertThat(provider.tryAcquire("order:1", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    @DisplayName("不同键互不影响")
    void differentKeysAreIndependent() {
        provider.tryAcquire("order:1", Duration.ofSeconds(30)).orElseThrow();

        assertThat(provider.tryAcquire("order:2", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    @DisplayName("释放后可重新获取，重复释放无副作用")
    void releaseAllowsReacquisitionAndIsIdempotent() {
        LockLease lease = provider.tryAcquire("order:1", Duration.ofSeconds(30)).orElseThrow();

        lease.close();
        lease.close();

        assertThat(provider.tryAcquire("order:1", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    @DisplayName("租约到期后可被其他调用方获取")
    void expiredLeaseCanBeReacquired() throws InterruptedException {
        provider.tryAcquire("order:1", Duration.ofMillis(SHORT_TTL_MS)).orElseThrow();

        Thread.sleep(WAIT_MS);

        assertThat(provider.tryAcquire("order:1", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    @DisplayName("acquire 在占用时抛 LOCK_BUSY（可重试）")
    void acquireThrowsWhenBusy() {
        provider.tryAcquire("order:1", Duration.ofSeconds(30)).orElseThrow();

        assertThatThrownBy(() -> provider.acquire("order:1", Duration.ofSeconds(30)))
                .isInstanceOfSatisfying(
                        LockException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(LockErrorCode.LOCK_BUSY);
                            assertThat(e.isRetryable()).isTrue();
                        });
    }

    @Test
    @DisplayName("空白键与非法租约显式失败")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> provider.tryAcquire(" ", Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(
                        LockException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LockErrorCode.INVALID_KEY));

        assertThatThrownBy(() -> provider.tryAcquire("order:1", Duration.ZERO))
                .isInstanceOfSatisfying(
                        LockException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LockErrorCode.INVALID_TTL));

        assertThatThrownBy(() -> provider.tryAcquire("order:1", Duration.ofSeconds(-1)))
                .isInstanceOfSatisfying(
                        LockException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LockErrorCode.INVALID_TTL));
    }
}
