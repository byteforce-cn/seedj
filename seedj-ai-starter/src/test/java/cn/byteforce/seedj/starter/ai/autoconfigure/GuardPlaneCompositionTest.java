package cn.byteforce.seedj.starter.ai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.guard.AgentGuardFilter;
import cn.byteforce.seedj.starter.ai.plane.DecisionController;
import cn.byteforce.seedj.starter.ai.plane.DecisionService;
import cn.byteforce.seedj.starter.ai.plane.JwksController;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenVerifier;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * guard 与决策面同进程装配：两侧各自声明 {@link ToolRegistry} 与 {@link DecisionTokenIssuer}，靠
 * {@code @ConditionalOnMissingBean} 收敛成单例。
 *
 * <p>这条约束以前只是「读起来对」：两个自动配置都写了 {@code @ConditionalOnMissingBean}，谁先注册是 Boot 的装配顺序
 * 决定的。同进程部署（T0 的默认形态：一个服务既当决策面又当执行侧）必须确有<b>唯一的</b>工具注册表与签发方，否则
 * 守卫验签用的公钥与决策面签发的公钥可能不是同一把，每个写命令都会 403。用例直接同时装两个配置来钉住它。
 */
class GuardPlaneCompositionTest {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("018f2e3a-0000-7000-8000-000000000118");

    private static final WebApplicationContextRunner RUNNER =
            new WebApplicationContextRunner()
                    .withUserConfiguration(Host.class)
                    .withConfiguration(
                            AutoConfigurations.of(
                                    AiGuardAutoConfiguration.class,
                                    DecisionPlaneAutoConfiguration.class))
                    .withPropertyValues(
                            "seedj.ai.plane.enabled=true",
                            "seedj.ai.decision.audience=seedj-ai-test",
                            "seedj.ai.identity.actor=" + ACTOR,
                            "seedj.ai.identity.on-behalf-of=" + ON_BEHALF_OF,
                            "seedj.ai.plane.tenant=" + TENANT);

    @Test
    @DisplayName("同进程：工具注册表与签发方各只有一个，守卫与决策面并存")
    void plane_and_guard_share_one_registry_and_one_issuer() {
        RUNNER.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ToolRegistry.class);
                    assertThat(context).hasSingleBean(DecisionTokenIssuer.class);
                    assertThat(context).hasSingleBean(AgentGuardFilter.class);
                    assertThat(context).hasSingleBean(DecisionTokenVerifier.class);
                    assertThat(context).hasSingleBean(DecisionService.class);
                    assertThat(context).hasSingleBean(DecisionController.class);
                    assertThat(context).hasSingleBean(JwksController.class);
                });
    }

    /** 装配期宿主：标记、时钟、账本与调用方视图；工具与签发方由两个自动配置自己收敛。 */
    @Configuration(proxyBeanMethods = false)
    static class Host {

        @Bean
        AiEnabledMarker aiEnabledMarker() {
            return new AiEnabledMarker();
        }

        @Bean
        Clock clock() {
            return () -> Instant.parse("2026-11-17T09:41:00Z");
        }

        @Bean
        DecisionLedger decisionLedger() {
            return new InMemoryDecisionLedger();
        }

        @Bean
        CallerProvider callerProvider() {
            return Optional::empty;
        }
    }
}
