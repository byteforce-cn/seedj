package cn.byteforce.seedj.starter.ai.runtime.llm;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.Proposal;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.runtime.LlmGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 开关、缺配置与宿主覆盖：LLM 客户端只应在「启用且配置完整」时出现。 */
class LlmAutoConfigurationTest {

    private static final ApplicationContextRunner RUNNER =
            new ApplicationContextRunner()
                    .withUserConfiguration(Host.class)
                    .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class))
                    .withPropertyValues(
                            "seedj.ai.llm.base-url=http://llm.test/v1",
                            "seedj.ai.llm.model=gpt-test",
                            "seedj.ai.tools[0].command-type=ticket.create",
                            "seedj.ai.tools[0].action-class=REVERSIBLE");

    @Test
    @DisplayName("默认关闭：没有 LLM 端口")
    void disabled_has_no_gateway() {
        RUNNER.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LlmGateway.class);
                });
    }

    @Test
    @DisplayName("启用且配置完整：装配 OpenAI 兼容端口")
    void enabled_wires_gateway() {
        RUNNER.withPropertyValues("seedj.ai.llm.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(LlmGateway.class);
                        });
    }

    @Test
    @DisplayName("启用但缺 model：启动失败并点名配置键")
    void enabled_without_model_fails_startup() {
        RUNNER.withPropertyValues("seedj.ai.llm.enabled=true", "seedj.ai.llm.model=")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(rootCauseMessages(context.getStartupFailure()))
                                    .contains("seedj.ai.llm.model");
                        });
    }

    @Test
    @DisplayName("宿主提供 LlmGateway 时让位")
    void host_gateway_wins() {
        RUNNER.withUserConfiguration(HostGateway.class)
                .withPropertyValues("seedj.ai.llm.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(LlmGateway.class))
                                    .isInstanceOf(StubGateway.class);
                        });
    }

    private static String rootCauseMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append(" | ");
        }
        return messages.toString();
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

    /** 宿主自定义端口：验证 {@code @ConditionalOnMissingBean} 的让位。 */
    @Configuration(proxyBeanMethods = false)
    static class HostGateway {

        @Bean
        LlmGateway hostGateway() {
            return new StubGateway();
        }
    }

    /** 宿主自定义端口：只要类型可断言，不参与真实调用。 */
    static final class StubGateway implements LlmGateway {

        @Override
        public Proposal next(cn.byteforce.seedj.starter.ai.runtime.Prompt prompt) {
            return new Proposal(
                    cn.byteforce.seedj.ai.DecisionKind.FINISH, null, null, null, "host", null);
        }
    }
}
