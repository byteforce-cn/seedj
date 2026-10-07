package cn.byteforce.seedj.core.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RequestContext} 契约测试：读写、清理、空值语义与异步传播。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class RequestContextTest {

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void emptyByDefault() {
        assertThat(RequestContext.traceId()).isEmpty();
        assertThat(RequestContext.userId()).isEmpty();
        assertThat(RequestContext.tenantId()).isEmpty();
        assertThat(RequestContext.username()).isEmpty();
    }

    @Test
    void storesAndReadsAllFields() {
        RequestContext.setTraceId("trace-1");
        RequestContext.setUserId(42L);
        RequestContext.setTenantId(7L);
        RequestContext.setUsername("alice");

        assertThat(RequestContext.traceId()).contains("trace-1");
        assertThat(RequestContext.userId()).contains(42L);
        assertThat(RequestContext.tenantId()).contains(7L);
        assertThat(RequestContext.username()).contains("alice");
    }

    @Test
    void blankStringsAreTreatedAsAbsent() {
        RequestContext.setTraceId("  ");
        RequestContext.setUsername("");

        assertThat(RequestContext.traceId()).isEmpty();
        assertThat(RequestContext.username()).isEmpty();
    }

    @Test
    void setIdentityReplacesAllIdentityFields() {
        RequestContext.setIdentity(1L, 2L, "bob");
        RequestContext.setIdentity(null, null, null);

        assertThat(RequestContext.userId()).isEmpty();
        assertThat(RequestContext.tenantId()).isEmpty();
        assertThat(RequestContext.username()).isEmpty();
    }

    @Test
    void clearRemovesContext() {
        RequestContext.setTraceId("t");
        RequestContext.clear();

        assertThat(RequestContext.traceId()).isEmpty();
    }

    @Test
    void wrapRunnablePropagatesContextAndRestoresPrevious() throws Exception {
        RequestContext.setTraceId("trace-parent");
        RequestContext.setUserId(1L);

        AtomicReference<String> observed = new AtomicReference<>();
        AtomicReference<String> after = new AtomicReference<>();
        Runnable task = RequestContext.wrap(() -> {
            observed.set(RequestContext.traceId().orElse(null));
            RequestContext.setTraceId("trace-child");
        });
        Runnable outer = () -> {
            RequestContext.setTraceId("trace-worker");
            task.run();
            after.set(RequestContext.traceId().orElse(null));
        };

        Thread thread = new Thread(outer);
        thread.start();
        thread.join();

        assertThat(observed).hasValue("trace-parent");
        assertThat(after).hasValue("trace-worker");
    }

    @Test
    void wrapCallableCapturesContextAcrossThreadBoundary() throws Exception {
        RequestContext.setTraceId("trace-async");
        RequestContext.setTenantId(9L);

        Callable<Long> callable = RequestContext.wrap(() -> {
            assertThat(RequestContext.traceId()).contains("trace-async");
            return RequestContext.tenantId().orElseThrow();
        });

        AtomicReference<Long> result = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                result.set(callable.call());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        thread.start();
        thread.join();

        assertThat(result).hasValue(9L);
    }

    @Test
    void wrapRejectsNullTask() {
        assertThatThrownBy(() -> RequestContext.wrap((Runnable) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RequestContext.wrap((Callable<Object>) null))
                .isInstanceOf(NullPointerException.class);
    }
}
