package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link EisAuthzAutoConfiguration} 装配矩阵测试（权威 / 消费方 / 端点）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuthzAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EisAuthzAutoConfiguration.class));

    @Test
    void engineRegistersWhenStorePresent() {
        runner.withBean(EisAuthzStore.class, () -> mock(EisAuthzStore.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EisAuthzEngine.class);
                    assertThat(context).hasSingleBean(EisAuthzOperations.class);
                    assertThat(context).doesNotHaveBean(EisAuthzClient.class);
                });
    }

    @Test
    void noEngineWithoutStore() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EisAuthzEngine.class);
        });
    }

    @Test
    void clientAndCacheRegisterWithBaseUrl() {
        runner.withPropertyValues("byteforce.eis.authz.base-url=http://authz.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EisAuthzClient.class);
                    assertThat(context).hasSingleBean(EisAuthzCachingOperations.class);
                });
    }

    @Test
    void disabledByPropertyRegistersNothing() {
        runner.withBean(EisAuthzStore.class, () -> mock(EisAuthzStore.class))
                .withPropertyValues("byteforce.eis.authz.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EisAuthzEngine.class);
                });
    }

    @Test
    void webRunnerRegistersControllerForAuthority() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EisAuthzAutoConfiguration.class))
                .withBean(EisAuthzStore.class, () -> mock(EisAuthzStore.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EisAuthzController.class);
                    assertThat(context).hasSingleBean(EisAuthzExceptionHandler.class);
                });
    }

    @Test
    void webRunnerRegistersControllerForConsumer() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EisAuthzAutoConfiguration.class))
                .withPropertyValues("byteforce.eis.authz.base-url=http://authz.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EisAuthzController.class);
                });
    }
}
