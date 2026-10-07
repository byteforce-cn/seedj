package cn.byteforce.seedj.starter.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeedjWebAutoConfiguration} 装配与开关契约测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class SeedjWebAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SeedjWebAutoConfiguration.class));

    @Test
    void registersDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(context).hasBean("seedjTraceContextFilter");
            assertThat(context).hasBean("seedjIdentityContextFilter");
        });
    }

    @Test
    void killSwitchDisablesEverything() {
        runner.withPropertyValues("byteforce.web.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
                    assertThat(context).doesNotHaveBean("seedjTraceContextFilter");
                    assertThat(context).doesNotHaveBean("seedjIdentityContextFilter");
                });
    }

    @Test
    void errorHandlingCanBeDisabledIndependently() {
        runner.withPropertyValues("byteforce.web.error-handling-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
                    assertThat(context).hasBean("seedjTraceContextFilter");
                });
    }

    @Test
    void requestContextFiltersCanBeDisabledIndependently() {
        runner.withPropertyValues("byteforce.web.request-context-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context).doesNotHaveBean("seedjTraceContextFilter");
                    assertThat(context).doesNotHaveBean("seedjIdentityContextFilter");
                });
    }
}
