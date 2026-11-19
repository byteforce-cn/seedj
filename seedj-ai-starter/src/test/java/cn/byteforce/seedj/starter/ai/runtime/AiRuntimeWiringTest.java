package cn.byteforce.seedj.starter.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.Proposal;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * runtime 装配的两条路径：没有 LLM 端口时只 WARN、不装配循环；有端口时循环与启动检查都在。
 *
 * <p>启动检查本身没有可断言的日志，但它是 {@link ApplicationRunner}：跑一遍就覆盖了「有空/没有端口」两条分支， 也保证它不会在启动期抛异常。
 */
class AiRuntimeWiringTest {

    private static final ApplicationContextRunner RUNNER =
            new ApplicationContextRunner()
                    .withUserConfiguration(Host.class)
                    .withConfiguration(AutoConfigurations.of(AiRuntimeAutoConfiguration.class))
                    .withPropertyValues(
                            "seedj.ai.runtime.enabled=true",
                            "seedj.ai.runtime.model.name=gpt-test",
                            "seedj.ai.runtime.model.version=1");

    @Test
    @DisplayName("没有 LlmGateway：循环不装配，启动检查照常完成")
    void runs_without_gateway() {
        RUNNER.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(BoundedLoop.class);
                    assertThat(context).hasSingleBean(ApplicationRunner.class);
                    context.getBean(ApplicationRunner.class).run(null);
                });
    }

    @Test
    @DisplayName("有 LlmGateway：循环装配，启动检查照常完成")
    void runs_with_gateway() {
        RUNNER.withUserConfiguration(GatewayHost.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(BoundedLoop.class);
                            context.getBean(ApplicationRunner.class).run(null);
                        });
    }

    /** 装配期宿主：AI 标记与 HTTP 构造器。 */
    @Configuration(proxyBeanMethods = false)
    static class Host {

        @Bean
        AiEnabledMarker aiEnabledMarker() {
            return new AiEnabledMarker();
        }

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    /** 宿主提供的 LLM 端口：让 BoundedLoop 有东西可装配。 */
    @Configuration(proxyBeanMethods = false)
    static class GatewayHost {

        @Bean
        LlmGateway scriptedGateway() {
            return prompt -> new Proposal(DecisionKind.FINISH, null, null, null, "done", List.of());
        }
    }
}
