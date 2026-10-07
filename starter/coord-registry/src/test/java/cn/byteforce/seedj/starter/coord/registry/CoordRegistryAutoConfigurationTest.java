package cn.byteforce.seedj.starter.coord.registry;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.registry.Registration;
import cn.byteforce.coord.sdk.registry.Registry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CoordRegistryAutoConfiguration} 装配 / 回退测试。
 *
 * <p>不依赖真实 Agent：{@link CoordClient} 为 mock，{@code registry()} 返回 mock
 * {@link Registry}（SDK 连接惰性，mock 不会建立任何连接）。</p>
 */
class CoordRegistryAutoConfigurationTest {

    private final Registry registry = mock(Registry.class);
    private final CoordClient coordClient = mock(CoordClient.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoordRegistryAutoConfiguration.class))
            .withBean(CoordClient.class, () -> {
                when(coordClient.registry()).thenReturn(registry);
                return coordClient;
            });

    @Test
    void registersBeansByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CoordDiscoveryClient.class);
            assertThat(context).hasSingleBean(CoordAutoRegistration.class);
            assertThat(context).hasBean("coordRegistrationReadyListener");
            assertThat(context).hasBean("coordWebServerPortListener");
        });
    }

    @Test
    void backsOffWhenDisabled() {
        runner.withPropertyValues("byteforce.coord.registry.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CoordDiscoveryClient.class);
            assertThat(context).doesNotHaveBean(CoordAutoRegistration.class);
        });
    }

    @Test
    void backsOffWithoutCoordClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoordRegistryAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CoordDiscoveryClient.class);
                    assertThat(context).doesNotHaveBean(CoordAutoRegistration.class);
                });
    }

    @Test
    void autoRegisterDisabledKeepsDiscoveryOnly() {
        runner.withPropertyValues("byteforce.coord.registry.auto-register=false").run(context -> {
            assertThat(context).hasSingleBean(CoordDiscoveryClient.class);
            assertThat(context).doesNotHaveBean(CoordAutoRegistration.class);
            assertThat(context).doesNotHaveBean("coordRegistrationReadyListener");
            assertThat(context).doesNotHaveBean("coordWebServerPortListener");
        });
    }

    @Test
    void userBeansTakePrecedence() {
        CoordDiscoveryClient custom = new CoordDiscoveryClient(registry,
                new CoordRegistryProperties().getDiscovery());
        runner.withBean(CoordDiscoveryClient.class, () -> custom).run(context ->
                assertThat(context.getBean(CoordDiscoveryClient.class)).isSameAs(custom));
    }

    @Test
    void webListenerSkippedWhenWebServerApiAbsent() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.boot.web.server"))
                .run(context -> {
                    assertThat(context).doesNotHaveBean("coordWebServerPortListener");
                    assertThat(context).hasSingleBean(CoordAutoRegistration.class);
                });
    }

    @Test
    void autoRegistrationWorksThroughRealWiring() {
        when(registry.register(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(mock(Registration.class));
        runner.withPropertyValues(
                        "spring.application.name=wired-app",
                        "byteforce.coord.registry.host=10.9.9.9")
                .run(context -> {
                    CoordAutoRegistration registration = context.getBean(CoordAutoRegistration.class);
                    registration.onPortAvailable(8088);
                    long deadline = System.nanoTime() + 5_000_000_000L;
                    while (!registration.isRegistered() && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                    assertThat(registration.isRegistered()).isTrue();
                });
    }
}
