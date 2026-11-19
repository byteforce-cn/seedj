package cn.byteforce.seedj.starter.ai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.policy.PolicyEvaluator;
import cn.byteforce.seedj.starter.ai.policy.PolicyHeadSource;
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
 * 策略端口的两侧装配：开关关闭时一个 bean 都没有，开启时决策面拿到求值器、守卫拿到 head 源，{@code verify=none}
 * 只影响是否参与校验。
 *
 * <p>这些断言钉住的是「配置键 → bean」这一段：端口本身由 {@code ConfiguredPolicyEvaluatorTest} 与
 * {@code DecisionTokenVerifierTest} 覆盖。
 */
class PolicyWiringTest {

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
    @DisplayName("策略默认关闭：两侧都没有策略 bean")
    void disabled_has_no_policy_beans() {
        RUNNER.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PolicyEvaluator.class);
                    assertThat(context).doesNotHaveBean(PolicyHeadSource.class);
                });
    }

    @Test
    @DisplayName("启用策略：决策面有求值器，守卫有参与校验的 head 源")
    void enabled_wires_both_sides() {
        RUNNER.withPropertyValues(
                        "seedj.ai.policy.enabled=true",
                        "seedj.ai.policy.bundle=ticket-policy",
                        "seedj.ai.policy.version=3",
                        "seedj.ai.policy.epoch=41")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(PolicyEvaluator.class);
                            PolicyHeadSource heads = context.getBean(PolicyHeadSource.class);
                            assertThat(heads.enforced()).isTrue();
                            assertThat(heads.head("local", "ticket-policy"))
                                    .hasValueSatisfying(
                                            head -> {
                                                assertThat(head.version()).isEqualTo(3L);
                                                assertThat(head.epoch()).isEqualTo(41L);
                                            });
                        });
    }

    @Test
    @DisplayName("verify=none：bean 仍在，但不参与校验")
    void verify_none_bean_is_not_enforced() {
        RUNNER.withPropertyValues(
                        "seedj.ai.policy.enabled=true",
                        "seedj.ai.policy.bundle=ticket-policy",
                        "seedj.ai.policy.version=3",
                        "seedj.ai.policy.verify=none")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(PolicyHeadSource.class).enforced())
                                    .isFalse();
                        });
    }

    @Test
    @DisplayName("宿主提供求值器时优先于配置实现")
    void host_evaluator_wins() {
        RUNNER.withUserConfiguration(HostEvaluator.class)
                .withPropertyValues(
                        "seedj.ai.policy.enabled=true",
                        "seedj.ai.policy.bundle=ticket-policy",
                        "seedj.ai.policy.version=3")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(PolicyEvaluator.class).evaluate(null))
                                    .isEqualTo(PolicyRef.none());
                        });
    }

    /** 装配期宿主：标记、时钟、账本与调用方视图。 */
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

    /** 宿主自定义的求值器：验证 {@code @ConditionalOnMissingBean} 的让位。 */
    @Configuration(proxyBeanMethods = false)
    static class HostEvaluator {

        @Bean
        PolicyEvaluator hostPolicyEvaluator() {
            return request -> PolicyRef.none();
        }
    }
}
