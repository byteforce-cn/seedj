package cn.byteforce.seedj.starter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.starter.autoconfigure.BizEnabledMarker;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** 资源服务器装配的契约：开关、bean 形状、链路顺序。 */
class BizSecurityAutoConfigurationTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(BizSecurityAutoConfiguration.class))
                    .withBean(BizEnabledMarker.class)
                    .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
                    .withPropertyValues(
                            "seedj.biz.issuer-uri=https://iam.test", "seedj.audit.enabled=false");

    @Test
    @DisplayName("开启后提供调用方、作用域、过滤器链与 JSON 错误响应")
    void registersResourceServerBeans() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CallerProvider.class);
                    assertThat(context.getBean(CallerProvider.class))
                            .isInstanceOf(JwtCallerProvider.class);
                    assertThat(context).hasSingleBean(CallerPropagation.class);
                    assertThat(context).hasSingleBean(TaskDecorator.class);
                    assertThat(context).hasBean("bizSecurityFilterChain");
                    assertThat(context).hasSingleBean(JsonAuthenticationEntryPoint.class);
                    assertThat(context).hasSingleBean(JsonAccessDeniedHandler.class);
                    assertThat(context).hasSingleBean(BizSecurityExceptionHandler.class);
                    assertThat(context).hasSingleBean(SecurityDenialAudit.class);
                });
    }

    @Test
    @DisplayName("过滤器链顺序固定为 6（在 IAM 浏览器登录链之前）")
    void filterChainOrderIsSix() throws Exception {
        Method method =
                BizSecurityAutoConfiguration.WebSecurityConfiguration.class.getMethod(
                        "bizSecurityFilterChain",
                        org.springframework.security.config.annotation.web.builders.HttpSecurity
                                .class,
                        BizSecurityProperties.class,
                        JwtDecoder.class,
                        org.springframework.security.oauth2.server.resource.authentication
                                .JwtAuthenticationConverter.class,
                        JsonAuthenticationEntryPoint.class,
                        JsonAccessDeniedHandler.class);
        var order =
                AnnotationUtils.findAnnotation(
                        method, org.springframework.core.annotation.Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(BizSecurityAutoConfiguration.BIZ_FILTER_CHAIN_ORDER);
        assertThat(SecurityFilterChain.class).isAssignableFrom(method.getReturnType());
    }

    @Test
    @DisplayName("seedj.biz.enabled=false 时不注册任何安全 bean")
    void disabledSwitchBacksOff() {
        runner.withPropertyValues("seedj.biz.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(CallerProvider.class);
                            assertThat(context).doesNotHaveBean("bizSecurityFilterChain");
                        });
    }

    @Test
    @DisplayName("public-paths 可配置，默认包含健康检查与错误页")
    void publicPathsAreConfigurable() {
        runner.withPropertyValues("seedj.biz.public-paths=/api/public/**,/actuator/health")
                .run(
                        context -> {
                            BizSecurityProperties properties =
                                    context.getBean(BizSecurityProperties.class);
                            assertThat(properties.publicPaths())
                                    .containsExactly("/api/public/**", "/actuator/health");
                            assertThat(properties.tenantId()).isEqualTo("default");
                            assertThat(properties.auditDenialsEnabled()).isTrue();
                        });

        BizSecurityProperties defaults =
                new BizSecurityProperties(null, null, null, null, null, null, null);
        assertThat(defaults.publicPaths()).isEqualTo(BizSecurityProperties.DEFAULT_PUBLIC_PATHS);
        assertThat(defaults.hasAudience()).isFalse();
        assertThat(defaults.publicPaths())
                .containsExactlyElementsOf(
                        List.of("/actuator/health", "/actuator/info", "/error", "/api/public/**"));
    }

    @Test
    @DisplayName("CORS 默认不放开；prod 下空配置启动失败")
    void corsFailsClosed() {
        runner.run(
                context ->
                        assertThat(
                                        context.getBean(BizSecurityProperties.class)
                                                .corsAllowedOrigins())
                                .isEmpty());

        runner.withPropertyValues("spring.profiles.active=prod")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .hasMessageContaining("cors.allowed-origins"));
    }

    @Test
    @DisplayName("prod + 显式 CORS：启动成功")
    void prodWithCorsStarts() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "seedj.biz.cors.allowed-origins=https://app.example.com")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("dev-auth 开启：提供本地链，不要求 issuer/JwtDecoder")
    void devAuthProvidesLocalChain() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BizSecurityAutoConfiguration.class))
                .withBean(BizEnabledMarker.class)
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "seedj.biz.dev-auth.enabled=true",
                        "seedj.biz.dev-auth.permissions=hr.employee.write,hr.employee.read",
                        "seedj.biz.dev-auth.attributes.org_units=D01")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasBean("devAuthSecurityFilterChain");
                            assertThat(context).doesNotHaveBean("bizSecurityFilterChain");
                            assertThat(context).doesNotHaveBean(JwtDecoder.class);
                            DevAuthProperties properties = context.getBean(DevAuthProperties.class);
                            assertThat(properties.permissions())
                                    .containsExactlyInAnyOrder(
                                            "hr.employee.write", "hr.employee.read");
                            assertThat(properties.attributes())
                                    .containsEntry("org_units", List.of("D01"));
                        });
    }

    @Test
    @DisplayName("prod + dev-auth：启动失败")
    void devAuthInProdFails() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "seedj.biz.cors.allowed-origins=https://app.example.com",
                        "seedj.biz.dev-auth.enabled=true")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .hasMessageContaining("dev-auth"));
    }
}
