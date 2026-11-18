package cn.byteforce.seedj.starter.ai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.starter.ai.annotation.EnableAIServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

/**
 * {@code @EnableAIServer} 开关语义与 {@code seedj.ai.*} 绑定。
 *
 * <p>本模块被 {@code seedj-it} 依赖，任何自动配置都会在 IAM/IM/HR 的测试上下文里被加载：所有 AI 能力 bean
 * 都必须由 {@link AiEnabledMarker} 门控，而标记只在应用写了 {@code @EnableAIServer} 时存在。
 */
class AiEnablementTest {

    private static final List<String> CAPABILITY_PACKAGES =
            List.of(
                    "cn.byteforce.seedj.starter.ai.guard",
                    "cn.byteforce.seedj.starter.ai.plane",
                    "cn.byteforce.seedj.starter.ai.runtime",
                    "cn.byteforce.seedj.starter.ai.autoconfigure");

    /** 自动配置清单里的 AI 配置类；清单为空也合法（能力配置由后续任务逐个登记）。 */
    private static final ApplicationContextRunner MODULE_RUNNER =
            new ApplicationContextRunner().withConfiguration(moduleAutoConfigurations());

    @Test
    @DisplayName("没有 @EnableAIServer 时，自动配置清单不产任何 AI bean")
    void marker_absent_means_no_ai_beans() {
        MODULE_RUNNER.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AiEnabledMarker.class);
                    assertThat(aiCapabilityBeanTypes(context)).isEmpty();
                });
    }

    @Test
    @DisplayName("seedj.ai.enabled=false 时连标记都不产")
    void master_switch_disables_everything() {
        new ApplicationContextRunner()
                .withUserConfiguration(AiHost.class)
                .withPropertyValues("seedj.ai.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(AiEnabledMarker.class);
                            assertThat(aiCapabilityBeanTypes(context)).isEmpty();
                        });
    }

    @Test
    @DisplayName("空属性时默认值逐项命中")
    void properties_bind_defaults() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .run(
                        context -> {
                            AiProperties properties = context.getBean(AiProperties.class);

                            assertThat(properties.enabled()).isTrue();
                            assertThat(properties.plane().enabled()).isFalse();
                            assertThat(properties.plane().reconcileDatasource()).isNull();
                            assertThat(properties.plane().reconcileTtl())
                                    .isEqualTo(Duration.ofMinutes(10));
                            assertThat(properties.guard().enabled()).isTrue();
                            assertThat(properties.runtime().enabled()).isFalse();
                            assertThat(properties.runtime().maxSteps()).isEqualTo(6);
                            assertThat(properties.identity().actor()).isNull();
                            assertThat(properties.identity().onBehalfOf()).isNull();
                            assertThat(properties.decision().signingKey()).isNull();
                            assertThat(properties.decision().kid())
                                    .isEqualTo("agent-decision-2026-11");
                            assertThat(properties.decision().ttl())
                                    .isEqualTo(Duration.ofSeconds(300));
                            assertThat(properties.decision().audience()).isNull();
                            assertThat(properties.tools()).isEmpty();
                            assertThat(properties.contracts()).isEmpty();
                        });
    }

    @Test
    @DisplayName("tools[].command-type/action-class 与 contracts[] 可绑定")
    void properties_bind_tools_and_contracts() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues(
                        "seedj.ai.tools[0].command-type=create_ticket",
                        "seedj.ai.tools[0].action-class=compensable",
                        "seedj.ai.contracts[0].id=create_ticket",
                        "seedj.ai.contracts[0].commands[0]=ticket.create",
                        "seedj.ai.contracts[0].commands[1]=ticket.comment")
                .run(
                        context -> {
                            AiProperties properties = context.getBean(AiProperties.class);

                            assertThat(properties.tools()).hasSize(1);
                            assertThat(properties.tools().get(0).commandType())
                                    .isEqualTo("create_ticket");
                            assertThat(properties.tools().get(0).actionClass())
                                    .isEqualTo(ActionClass.COMPENSABLE);
                            assertThat(properties.contracts()).hasSize(1);
                            assertThat(properties.contracts().get(0).id())
                                    .isEqualTo("create_ticket");
                            assertThat(properties.contracts().get(0).commands())
                                    .containsExactly("ticket.create", "ticket.comment");
                        });
    }

    @Test
    @DisplayName("@EnableAIServer 注册标记")
    void enable_annotation_registers_marker() {
        new ApplicationContextRunner()
                .withUserConfiguration(AiHost.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(AiEnabledMarker.class);
                        });
    }

    private static List<String> aiCapabilityBeanTypes(AssertableApplicationContext context) {
        List<String> types = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null) {
                continue;
            }
            String packageName = type.getPackageName();
            if (CAPABILITY_PACKAGES.stream().anyMatch(packageName::startsWith)) {
                types.add(type.getName());
            }
        }
        return types;
    }

    private static AutoConfigurations moduleAutoConfigurations() {
        ClassLoader classLoader = AiEnablementTest.class.getClassLoader();
        List<Class<?>> classes = new ArrayList<>();
        for (String candidate : ImportCandidates.load(AutoConfiguration.class, classLoader)) {
            if (candidate.startsWith("cn.byteforce.seedj.starter.ai.")) {
                classes.add(ClassUtils.resolveClassName(candidate, classLoader));
            }
        }
        return AutoConfigurations.of(classes.toArray(Class<?>[]::new));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAIServer
    static class AiHost {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class PropertiesConfig {}
}
