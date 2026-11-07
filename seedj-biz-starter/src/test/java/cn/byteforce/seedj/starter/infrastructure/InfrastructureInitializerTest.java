package cn.byteforce.seedj.starter.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.InfrastructureErrorCode;
import cn.byteforce.seedj.core.InfrastructureException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 启动期能力执行器：顺序、就绪短路、必需/可选失败语义。 */
class InfrastructureInitializerTest {

    @Test
    @DisplayName("按 getOrder() 执行，而不是按注册顺序")
    void runsCapabilitiesInOrder() {
        List<String> calls = new ArrayList<>();
        StubCapability late = new StubCapability("late", 20, calls);
        StubCapability early = new StubCapability("early", 10, calls);

        initializer(late, early).afterPropertiesSet();

        assertThat(calls)
                .containsExactly(
                        "early:ready",
                        "early:init",
                        "early:validate",
                        "late:ready",
                        "late:init",
                        "late:validate");
    }

    @Test
    @DisplayName("已就绪的能力不再 initialize / validate")
    void skipsReadyCapability() {
        List<String> calls = new ArrayList<>();
        StubCapability ready = new StubCapability("db", 0, calls);
        ready.ready = true;

        initializer(ready).afterPropertiesSet();

        assertThat(calls).containsExactly("db:ready");
    }

    @Test
    @DisplayName("没有能力时启动流程直接结束")
    void noCapabilitiesIsNoop() {
        assertThatCode(() -> initializer().afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("必需能力初始化失败 → 包装成 INITIALIZATION_FAILED 并阻断后续能力")
    void requiredFailureAbortsStartup() {
        List<String> calls = new ArrayList<>();
        StubCapability broken = new StubCapability("db", 0, calls);
        broken.initError = new IllegalStateException("cannot migrate");
        StubCapability after = new StubCapability("after", 10, calls);

        assertThatThrownBy(() -> initializer(broken, after).afterPropertiesSet())
                .isInstanceOfSatisfying(
                        InfrastructureException.class,
                        e -> {
                            assertThat(e.code())
                                    .isEqualTo(
                                            InfrastructureErrorCode.INITIALIZATION_FAILED.name());
                            assertThat(e.getMessage()).contains("[db]");
                            assertThat(e.getCause()).hasMessage("cannot migrate");
                        });
        assertThat(calls).doesNotContain("after:ready");
    }

    @Test
    @DisplayName("可选能力失败只记录 WARN，继续执行后续能力")
    void optionalFailureIsTolerated() {
        List<String> calls = new ArrayList<>();
        StubCapability optional = new StubCapability("cache", 0, calls);
        optional.required = false;
        optional.initError = new IllegalStateException("redis down");
        StubCapability next = new StubCapability("db", 10, calls);

        initializer(optional, next).afterPropertiesSet();

        assertThat(calls).containsSubsequence("cache:init", "db:ready", "db:init", "db:validate");
    }

    @Test
    @DisplayName("validate 未通过也算失败：必需能力同样阻断启动")
    void validationFailureAbortsStartup() {
        List<String> calls = new ArrayList<>();
        StubCapability capability = new StubCapability("db", 0, calls);
        capability.validateError =
                new InfrastructureException(InfrastructureErrorCode.NOT_READY, "table missing");

        assertThatThrownBy(() -> initializer(capability).afterPropertiesSet())
                .isInstanceOfSatisfying(
                        InfrastructureException.class,
                        e ->
                                assertThat(e.code())
                                        .isEqualTo(
                                                InfrastructureErrorCode.INITIALIZATION_FAILED
                                                        .name()));
    }

    @Test
    @DisplayName("装配层注册 InfrastructureInitializer bean")
    void autoConfigurationRegistersInitializer() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InfrastructureAutoConfiguration.class))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(InfrastructureInitializer.class);
                        });
    }

    private static InfrastructureInitializer initializer(InfrastructureCapability... capabilities) {
        @SuppressWarnings("unchecked")
        ObjectProvider<InfrastructureCapability> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> List.of(capabilities).stream());
        return new InfrastructureInitializer(provider);
    }

    static final class StubCapability implements InfrastructureCapability {

        private final String name;
        private final int order;
        private final List<String> calls;
        boolean ready;
        boolean required = true;
        RuntimeException initError;
        RuntimeException validateError;

        StubCapability(String name, int order, List<String> calls) {
            this.name = name;
            this.order = order;
            this.calls = calls;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public boolean isReady() {
            calls.add(name + ":ready");
            return ready;
        }

        @Override
        public void initialize() {
            calls.add(name + ":init");
            if (initError != null) {
                throw initError;
            }
        }

        @Override
        public void validate() {
            calls.add(name + ":validate");
            if (validateError != null) {
                throw validateError;
            }
        }

        @Override
        public boolean isRequired() {
            return required;
        }
    }
}
