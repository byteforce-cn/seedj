package cn.byteforce.seedj.starter.coord.lock;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.lock.LockClient;
import cn.byteforce.coord.sdk.lock.LockInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * {@link CoordLockService} / {@link CoordLockLease} 语义测试（C3）：
 * 获取（立即 / 轮询 / 超时 / 中断）、看门狗续约、失锁回调、释放（幂等 / 失锁跳过）。
 */
class CoordLockServiceTest {

    private final LockClient lockClient = mock(LockClient.class);
    private final List<CoordLockLease> lost = new CopyOnWriteArrayList<>();

    private CoordLockService service() {
        return new CoordLockService(lockClient, "holder-1", Duration.ofSeconds(30),
                List.of(lost::add), Duration.ofMillis(30));
    }

    @AfterEach
    void tearDown() {
        // 每个测试自建的 service 在用例内 close；此处仅清理回调记录
        lost.clear();
    }

    private static LockInfo acquired(long leaseId) {
        return new LockInfo("lock", "holder-1", leaseId, 0L, 30L, true);
    }

    private static LockInfo notAcquired() {
        return new LockInfo("lock", "", 0L, 0L, 30L, true);
    }

    @Test
    void immediateAcquireReturnsValidLease() {
        when(lockClient.acquire(eq("lock"), eq("holder-1"), anyLong())).thenReturn(acquired(42));
        CoordLockService service = service();
        try {
            Optional<CoordLockLease> lease = service.acquire("lock");

            assertThat(lease).isPresent();
            assertThat(lease.get().isValid()).isTrue();
            assertThat(lease.get().getHolderId()).isEqualTo("holder-1");
            assertThat(lease.get().getLeaseId()).isEqualTo(42);
            lease.get().release();
        } finally {
            service.close();
        }
    }

    @Test
    void watchdogRenewsPeriodically() {
        when(lockClient.acquire(anyString(), anyString(), anyLong())).thenReturn(acquired(1));
        when(lockClient.renew(anyString(), anyString(), anyLong())).thenReturn(true);
        CoordLockService service = service();
        try {
            CoordLockLease lease = service.acquire("lock").orElseThrow();
            awaitUntil(() -> renewCalls() >= 2);
            assertThat(lease.isValid()).isTrue();
            lease.release();
        } finally {
            service.close();
        }
    }

    @Test
    void renewFailureMarksLostAndNotifiesOnceThenStops() {
        when(lockClient.acquire(anyString(), anyString(), anyLong())).thenReturn(acquired(7));
        when(lockClient.renew(anyString(), anyString(), anyLong())).thenReturn(false);
        CoordLockService service = service();
        try {
            CoordLockLease lease = service.acquire("lock").orElseThrow();

            awaitUntil(() -> !lost.isEmpty());
            assertThat(lease.isValid()).isFalse();
            int afterLost = renewCalls();
            sleep(120);
            assertThat(renewCalls()).as("失锁后不再续约").isEqualTo(afterLost);
            assertThat(lost).hasSize(1);
        } finally {
            service.close();
        }
    }

    @Test
    void renewExceptionIsTreatedAsLost() {
        when(lockClient.acquire(anyString(), anyString(), anyLong())).thenReturn(acquired(7));
        when(lockClient.renew(anyString(), anyString(), anyLong()))
                .thenThrow(new CoordException(ErrorCode.AGENT_UNAVAILABLE, "agent 不可达"));
        CoordLockService service = service();
        try {
            CoordLockLease lease = service.acquire("lock").orElseThrow();

            awaitUntil(() -> !lost.isEmpty());
            assertThat(lease.isValid()).isFalse();
        } finally {
            service.close();
        }
    }

    @Test
    void releaseAfterLostSkipsRemoteCall() {
        when(lockClient.acquire(anyString(), anyString(), anyLong())).thenReturn(acquired(9));
        when(lockClient.renew(anyString(), anyString(), anyLong())).thenReturn(false);
        CoordLockService service = service();
        try {
            CoordLockLease lease = service.acquire("lock").orElseThrow();
            awaitUntil(() -> !lost.isEmpty());

            lease.release();

            assertThat(releaseCalls()).as("失锁后释放不得对新持有者发 RPC").isZero();
        } finally {
            service.close();
        }
    }

    @Test
    void releaseIsIdempotentAndStopsWatchdog() {
        when(lockClient.acquire(anyString(), anyString(), anyLong())).thenReturn(acquired(11));
        when(lockClient.release(anyString(), anyString(), anyLong())).thenReturn(true);
        when(lockClient.renew(anyString(), anyString(), anyLong())).thenReturn(true);
        CoordLockService service = service();
        try {
            CoordLockLease lease = service.acquire("lock").orElseThrow();

            lease.release();
            lease.release();
            int renewsAfterRelease = renewCalls();
            sleep(120);

            assertThat(releaseCalls()).isEqualTo(1);
            assertThat(renewCalls()).as("释放后看门狗停止").isEqualTo(renewsAfterRelease);
            assertThat(lease.isValid()).isFalse();
            assertThat(releaseCalls()).isEqualTo(1);
        } finally {
            service.close();
        }
    }

    @Test
    void acquireWaitsAndSucceedsAfterRetry() {
        when(lockClient.acquire(anyString(), anyString(), anyLong()))
                .thenReturn(notAcquired(), acquired(5));
        CoordLockService service = service();
        try {
            Optional<CoordLockLease> lease = service.acquire("lock", Duration.ofSeconds(2));

            assertThat(lease).isPresent();
            assertThat(lease.get().getLeaseId()).isEqualTo(5);
            lease.get().release();
        } finally {
            service.close();
        }
    }

    @Test
    void acquireTimesOutWhenContended() {
        when(lockClient.acquire(anyString(), anyString(), anyLong())).thenReturn(notAcquired());
        CoordLockService service = service();
        try {
            long start = System.nanoTime();
            Optional<CoordLockLease> lease = service.acquire("lock", Duration.ofMillis(250));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(lease).isEmpty();
            assertThat(elapsedMillis).isGreaterThanOrEqualTo(200);
            assertThat(acquireCalls()).as("等待窗口内多次轮询").isGreaterThanOrEqualTo(2);
        } finally {
            service.close();
        }
    }

    @Test
    void acquireOrThrowRaisesTimeoutException() {
        when(lockClient.acquire(anyString(), anyString(), anyLong())).thenReturn(notAcquired());
        CoordLockService service = service();
        try {
            assertThatThrownBy(() -> service.acquireOrThrow("lock", Duration.ZERO))
                    .isInstanceOf(CoordLockTimeoutException.class)
                    .hasMessageContaining("lock");
        } finally {
            service.close();
        }
    }

    @Test
    void interruptedWaitThrowsTimeoutException() throws Exception {
        when(lockClient.acquire(anyString(), anyString(), anyLong())).thenReturn(notAcquired());
        CoordLockService service = service();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                service.acquire("lock", Duration.ofSeconds(30));
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        try {
            thread.start();
            sleep(150);
            thread.interrupt();
            thread.join(3_000);

            assertThat(thrown.get()).isInstanceOf(CoordLockTimeoutException.class);
            assertThat(thrown.get()).hasMessageContaining("中断");
        } finally {
            service.close();
        }
    }

    @Test
    void acquireFailurePropagatesCoordException() {
        when(lockClient.acquire(anyString(), anyString(), anyLong()))
                .thenThrow(new CoordException(ErrorCode.AGENT_UNAVAILABLE, "agent 不可达"));
        CoordLockService service = service();
        try {
            assertThatThrownBy(() -> service.acquire("lock"))
                    .isInstanceOf(CoordException.class)
                    .hasMessageContaining("agent 不可达");
        } finally {
            service.close();
        }
    }

    private int renewCalls() {
        return countInvocations("renew");
    }

    private int releaseCalls() {
        return countInvocations("release");
    }

    private int acquireCalls() {
        return countInvocations("acquire");
    }

    private int countInvocations(String methodName) {
        return (int) mockingDetails(lockClient).getInvocations().stream()
                .filter(invocation -> methodName.equals(invocation.getMethod().getName()))
                .count();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10);
        }
        throw new AssertionError("等待超时（10s）");
    }
}
