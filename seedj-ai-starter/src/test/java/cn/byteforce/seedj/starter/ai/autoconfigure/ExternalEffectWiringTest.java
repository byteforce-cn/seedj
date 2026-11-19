package cn.byteforce.seedj.starter.ai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.infra.local.ai.InMemoryExternalEffectStore;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.effect.ExternalEffectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 外部效果自动配置：有存储 + 标记才装配。 */
class ExternalEffectWiringTest {

    private static final ApplicationContextRunner RUNNER =
            new ApplicationContextRunner()
                    .withUserConfiguration(Host.class)
                    .withConfiguration(
                            AutoConfigurations.of(ExternalEffectAutoConfiguration.class));

    @Test
    @DisplayName("有存储：装配服务")
    void wiresService() {
        RUNNER.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ExternalEffectService.class);
                });
    }

    @Test
    @DisplayName("没有存储：不装配")
    void noStoreDoesNotWire() {
        new ApplicationContextRunner()
                .withUserConfiguration(WithoutStore.class)
                .withConfiguration(AutoConfigurations.of(ExternalEffectAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(ExternalEffectService.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class Host {

        @Bean
        AiEnabledMarker aiEnabledMarker() {
            return new AiEnabledMarker();
        }

        @Bean
        cn.byteforce.seedj.ai.spi.ExternalEffectStore externalEffectStore() {
            return new InMemoryExternalEffectStore();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WithoutStore {

        @Bean
        AiEnabledMarker aiEnabledMarker() {
            return new AiEnabledMarker();
        }
    }
}
