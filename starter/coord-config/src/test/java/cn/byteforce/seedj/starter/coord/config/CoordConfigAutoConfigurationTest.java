package cn.byteforce.seedj.starter.coord.config;

import cn.byteforce.coord.sdk.CoordClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CoordConfigAutoConfiguration} 装配 / 回退测试。
 */
class CoordConfigAutoConfigurationTest {

    private final CoordClient coordClient = mock(CoordClient.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoordConfigAutoConfiguration.class))
            .withBean(CoordClient.class, () -> coordClient);

    @Test
    void registersRefreshChainByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CoordRefreshableBeansPostProcessor.class);
            assertThat(context).hasSingleBean(CoordConfigurationPropertiesRebinder.class);
            assertThat(context).hasSingleBean(CoordConfigRefreshManager.class);
        });
    }

    @Test
    void backsOffWhenDisabled() {
        runner.withPropertyValues("byteforce.coord.config.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CoordConfigRefreshManager.class);
            assertThat(context).doesNotHaveBean(CoordConfigurationPropertiesRebinder.class);
        });
    }

    @Test
    void refreshDisabledKeepsRebinderOnly() {
        runner.withPropertyValues("byteforce.coord.config.refresh.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CoordConfigRefreshManager.class);
            assertThat(context).hasSingleBean(CoordConfigurationPropertiesRebinder.class);
        });
    }

    @Test
    void backsOffManagerWithoutCoordClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoordConfigAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CoordConfigRefreshManager.class);
                    assertThat(context).hasSingleBean(CoordConfigurationPropertiesRebinder.class);
                });
    }

    @Test
    void toleratesBeansInitializedWithoutBeanDefinition() {
        runner.run(context -> {
            // 模拟测试框架行为：对无 BeanDefinition 的实例手工 initializeBean（BPP 不得抛错）
            Object foreign = context.getBeanFactory().initializeBean(new Object(), "foreign.ORIGINAL");
            assertThat(foreign).isNotNull();
            assertThat(context.getBean(CoordRefreshableBeansPostProcessor.class).refreshableBeans())
                    .doesNotContain("foreign.ORIGINAL");
        });
    }
}
