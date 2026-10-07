package cn.byteforce.seedj.starter.coord.registry;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.registry.Registration;
import cn.byteforce.coord.sdk.registry.Registry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CoordAutoRegistration} 生命周期语义测试（C1 / C5）：注册、退避重试、
 * 心跳失败重建、优雅注销、缺省跳过。
 */
class CoordAutoRegistrationTest {

    private final Registry registry = mock(Registry.class);
    private final Registration registrationHandle = mock(Registration.class);
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.application.name", "orders");
    private final List<CoordRegistrationListener> listeners = new java.util.ArrayList<>();

    private final RecordingListener listener = new RecordingListener();

    private CoordAutoRegistration newRegistration() {
        listeners.add(listener);
        CoordRegistryProperties properties = new CoordRegistryProperties();
        properties.setHost("10.0.0.1");
        properties.getMetadata().put("zone", "az-1");
        return new CoordAutoRegistration(registry, properties, environment, listeners,
                Duration.ofMillis(10), Duration.ofMillis(20));
    }

    @AfterEach
    void tearDown() {
        listeners.clear();
    }

    @Test
    void registersWithResolvedIdentityAndMetadataSchema() {
        when(registry.register(anyString(), anyString(), anyString(), anyInt())).thenReturn(registrationHandle);
        CoordAutoRegistration registration = newRegistration();

        registration.onPortAvailable(8081);

        awaitUntil(() -> registration.isRegistered());
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(registry).register(eq("orders"), eq("10.0.0.1:8081"), metadata.capture(), eq(30));
        String json = metadata.getValue();
        assertThat(JsonMapper.builder().build().readTree(json).get("address").asString()).isEqualTo("10.0.0.1:8081");
        assertThat(JsonMapper.builder().build().readTree(json).get("protocol").asString()).isEqualTo("http");
        assertThat(JsonMapper.builder().build().readTree(json).get("zone").asString()).isEqualTo("az-1");
        assertThat(listener.registered).hasValue(1);
        assertThat(registration.registeredInstance()).isPresent();
    }

    @Test
    void explicitServiceNameAndInstanceIdWin() {
        when(registry.register(anyString(), anyString(), anyString(), anyInt())).thenReturn(registrationHandle);
        listeners.add(listener);
        CoordRegistryProperties properties = new CoordRegistryProperties();
        properties.setServiceName("custom-svc");
        properties.setInstanceId("node-a");
        CoordAutoRegistration registration = new CoordAutoRegistration(registry, properties, environment,
                listeners, Duration.ofMillis(10), Duration.ofMillis(20));

        registration.onPortAvailable(9090);

        awaitUntil(() -> registration.isRegistered());
        verify(registry).register(eq("custom-svc"), eq("node-a"), anyString(), eq(30));
    }

    @Test
    void skipsWhenServiceNameMissing() {
        listeners.add(listener);
        CoordRegistryProperties properties = new CoordRegistryProperties();
        properties.setHost("10.0.0.1");
        CoordAutoRegistration registration = new CoordAutoRegistration(registry, properties, new MockEnvironment(),
                listeners, Duration.ofMillis(10), Duration.ofMillis(20));

        registration.onPortAvailable(8081);
        sleep(150);

        verify(registry, never()).register(anyString(), anyString(), anyString(), anyInt());
        assertThat(registration.isRegistered()).isFalse();
    }

    @Test
    void skipsWhenPortUnknown() {
        CoordAutoRegistration registration = newRegistration();

        registration.onApplicationReady(environment);
        registration.onApplicationReady(new MockEnvironment().withProperty("server.port", "0"));
        sleep(150);

        verify(registry, never()).register(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void registersOnApplicationReadyWithFixedPort() {
        when(registry.register(anyString(), anyString(), anyString(), anyInt())).thenReturn(registrationHandle);
        CoordAutoRegistration registration = newRegistration();

        registration.onApplicationReady(new MockEnvironment().withProperty("server.port", "9090"));

        awaitUntil(() -> registration.isRegistered());
        verify(registry).register(eq("orders"), anyString(), anyString(), eq(30));
    }

    @Test
    void retriesWithBackoffUntilSuccess() {
        when(registry.register(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new CoordException(ErrorCode.AGENT_UNAVAILABLE, "agent 不可达"))
                .thenThrow(new CoordException(ErrorCode.AGENT_UNAVAILABLE, "agent 不可达"))
                .thenReturn(registrationHandle);
        CoordAutoRegistration registration = newRegistration();

        registration.onPortAvailable(8081);

        awaitUntil(() -> registration.isRegistered());
        verify(registry, times(3)).register(anyString(), anyString(), anyString(), anyInt());
        assertThat(listener.failed).isEqualTo(2);
        assertThat(listener.registered).hasValue(1);
    }

    @Test
    void heartbeatFailureRebuildsRegistration() {
        Registration secondHandle = mock(Registration.class);
        when(registry.register(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(registrationHandle, secondHandle);
        CoordAutoRegistration registration = newRegistration();
        registration.onPortAvailable(8081);
        awaitUntil(() -> registration.isRegistered());

        ArgumentCaptor<Registration.HeartbeatFailedCallback> callback =
                ArgumentCaptor.forClass(Registration.HeartbeatFailedCallback.class);
        verify(registrationHandle).onHeartbeatFailed(callback.capture());
        callback.getValue().onHeartbeatFailed(registrationHandle);

        awaitUntil(() -> {
            try {
                verify(registry, times(2)).register(anyString(), anyString(), anyString(), anyInt());
                return true;
            } catch (Throwable notYet) {
                return false;
            }
        });
        verify(registrationHandle).close(any(Duration.class));
        assertThat(registration.isRegistered()).isTrue();
        assertThat(listener.registered).as("重建不重复通知首次注册").hasValue(1);
    }

    @Test
    void stopDeregistersAndNotifiesListener() {
        when(registry.register(anyString(), anyString(), anyString(), anyInt())).thenReturn(registrationHandle);
        CoordAutoRegistration registration = newRegistration();
        registration.onPortAvailable(8081);
        awaitUntil(() -> registration.isRegistered());

        registration.stop();

        verify(registrationHandle).close(any(Duration.class));
        assertThat(listener.deregistered).hasValue(1);
        assertThat(registration.isRunning()).isFalse();
        assertThat(registration.isRegistered()).isFalse();
    }

    @Test
    void stopBeforePortAvailablePreventsRegistration() {
        CoordAutoRegistration registration = newRegistration();

        registration.stop();
        registration.onPortAvailable(8081);
        sleep(100);

        verify(registry, never()).register(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void stopIsIdempotent() {
        when(registry.register(anyString(), anyString(), anyString(), anyInt())).thenReturn(registrationHandle);
        CoordAutoRegistration registration = newRegistration();
        registration.onPortAvailable(8081);
        awaitUntil(() -> registration.isRegistered());

        registration.stop();
        registration.stop();

        verify(registrationHandle, times(1)).close(any(Duration.class));
        assertThat(listener.deregistered).hasValue(1);
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

    /** 记录回调次数的监听器。 */
    private static final class RecordingListener implements CoordRegistrationListener {

        private final AtomicInteger registered = new AtomicInteger();
        private final AtomicInteger deregistered = new AtomicInteger();
        private volatile int failed;

        @Override
        public void onRegistered(CoordServiceInstance instance) {
            registered.incrementAndGet();
        }

        @Override
        public void onDeregistered(CoordServiceInstance instance) {
            deregistered.incrementAndGet();
        }

        @Override
        public void onRegisterFailed(String serviceName, int attempt, Throwable cause) {
            failed = attempt;
        }
    }
}
