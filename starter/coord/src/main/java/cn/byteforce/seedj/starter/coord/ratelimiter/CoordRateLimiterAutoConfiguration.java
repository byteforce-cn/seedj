package cn.byteforce.seedj.starter.coord.ratelimiter;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.ratelimiter.RateLimiterClient;
import cn.byteforce.seedj.starter.coord.CoordAutoConfiguration;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * Coord 限流封装自动配置（Phase 2，C4）：专用 API + 可选注解 AOP。
 *
 * <p>AOP 语义（文档明示）：Advisor 由容器默认 AOP 代理设施应用
 * （{@code spring.aop.auto} 未被关闭即可；@AspectJ weaver 不是必需依赖）；
 * 被限流抛 {@link CoordRateLimitExceededException}。**按实例（Agent 本地）** 语义，
 * 不承诺集群级限流。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass({CoordClient.class, RateLimiterClient.class})
@AutoConfigureAfter(CoordAutoConfiguration.class)
@ConditionalOnBean(CoordClient.class)
@ConditionalOnProperty(
        prefix = "byteforce.coord.ratelimiter",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(CoordRateLimiterProperties.class)
public class CoordRateLimiterAutoConfiguration {

    /**
     * 限流专用 API。
     */
    @Bean
    @ConditionalOnMissingBean
    public CoordRateLimiter coordRateLimiter(CoordClient coordClient) {
        return new CoordRateLimiter(coordClient.rateLimiter());
    }

    /**
     * 注解 AOP（可选；镜像全部前置条件——成员配置类不受外层 REGISTER 阶段条件保护）。
     *
     * <p>Advisor 以 {@code ROLE_INFRASTRUCTURE} 注册：这是 Spring 生态「库级自动代理」的
     * 标准机制（与事务基础设施同口径）——默认 Boot 应用（无 aspectjweaver）注册的
     * {@code InfrastructureAdvisorAutoProxyCreator} 只应用基础设施角色的 Advisor；
     * 应用存在 aspectjweaver 时 Boot 注册 {@code AnnotationAwareAspectJAutoProxyCreator}，
     * 全部 Advisor 均生效。两种形态均无需本模块引入 aspectjweaver。若使用方关闭全部
     * AOP（{@code spring.aop.auto=false}），请同步设
     * {@code byteforce.coord.ratelimiter.aop-enabled=false}。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MethodInterceptor.class)
    @ConditionalOnBean(CoordClient.class)
    @ConditionalOnProperty(
            prefix = "byteforce.coord.ratelimiter",
            name = {"enabled", "aop-enabled"},
            havingValue = "true",
            matchIfMissing = true
    )
    static class AopConfiguration {

        /**
         * {@link CoordRateLimit} Advisor：方法级 + 类型级注解匹配（继承检查开启）；
         * 基础设施角色（默认代理器可见），不依赖 aspectjweaver。
         */
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @ConditionalOnMissingBean(name = "coordRateLimitAdvisor")
        DefaultPointcutAdvisor coordRateLimitAdvisor(CoordRateLimiter rateLimiter) {
            Pointcut pointcut = new ComposablePointcut(
                    new AnnotationMatchingPointcut(CoordRateLimit.class, true));
            ((ComposablePointcut) pointcut).union(new AnnotationMatchingPointcut(null, CoordRateLimit.class, true));
            return new DefaultPointcutAdvisor(pointcut, new CoordRateLimitInterceptor(rateLimiter));
        }
    }
}
