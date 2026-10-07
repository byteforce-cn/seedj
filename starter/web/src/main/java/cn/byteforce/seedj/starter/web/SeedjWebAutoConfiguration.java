package cn.byteforce.seedj.starter.web;

import cn.byteforce.seedj.web.core.RequestContextContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.List;

/**
 * seedj Web 运行时自动配置：全局异常处理 + 请求上下文过滤器（traceId / 身份 / MDC）。
 *
 * <p>可通过 {@code byteforce.web.enabled=false} 整体关闭；
 * {@code byteforce.web.error-handling-enabled=false} 或
 * {@code byteforce.web.request-context-enabled=false} 可分别关闭子能力。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "byteforce.web", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SeedjWebProperties.class)
public class SeedjWebAutoConfiguration {

    /**
     * traceId 过滤器顺序：位于 Spring Security 过滤链（-100）之前。
     */
    public static final int TRACE_FILTER_ORDER = -150;

    /**
     * 身份过滤器顺序：位于 Spring Security 过滤链（-100）之后，此时安全上下文已就绪。
     */
    public static final int IDENTITY_FILTER_ORDER = -90;

    /**
     * 全局异常处理器（统一错误响应契约）。
     *
     * @return 异常处理器 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "byteforce.web", name = "error-handling-enabled",
            havingValue = "true", matchIfMissing = true)
    public GlobalExceptionHandler seedjGlobalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * traceId 过滤器注册。
     *
     * @param properties Web 运行时属性
     * @return 过滤器注册
     */
    @Bean
    @ConditionalOnMissingBean(TraceContextFilter.class)
    @ConditionalOnProperty(prefix = "byteforce.web", name = "request-context-enabled",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<TraceContextFilter> seedjTraceContextFilter(SeedjWebProperties properties) {
        FilterRegistrationBean<TraceContextFilter> registration =
                new FilterRegistrationBean<>(new TraceContextFilter(properties));
        registration.setOrder(TRACE_FILTER_ORDER);
        return registration;
    }

    /**
     * 身份上下文过滤器注册（调度 {@link RequestContextContributor}）。
     *
     * @param contributors 身份贡献器（可为空）
     * @return 过滤器注册
     */
    @Bean
    @ConditionalOnMissingBean(IdentityContextFilter.class)
    @ConditionalOnProperty(prefix = "byteforce.web", name = "request-context-enabled",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<IdentityContextFilter> seedjIdentityContextFilter(
            List<RequestContextContributor> contributors) {
        FilterRegistrationBean<IdentityContextFilter> registration =
                new FilterRegistrationBean<>(new IdentityContextFilter(contributors));
        registration.setOrder(IDENTITY_FILTER_ORDER);
        return registration;
    }
}
