package cn.byteforce.seedj.starter.eis.core;

import cn.byteforce.seedj.starter.eis.core.web.EisCommandController;
import cn.byteforce.seedj.starter.eis.core.web.EisCommandExceptionHandler;
import cn.byteforce.seedj.starter.eis.core.web.SecurityContextEisActorResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.Optional;
import java.util.UUID;

/**
 * EIS 核心自动配置：命令运行时、双时间轴对象存储、事件与 outbox、命令端点。
 *
 * <p>装配边界：</p>
 * <ul>
 *   <li>前置：{@code JdbcTemplate}（Boot JDBC 自动配置提供）与
 *       {@link PlatformTransactionManager}；</li>
 *   <li><b>审计是必配能力</b>：容器必须存在 {@link EisAuditAppender} Bean
 *       （由 {@code starter-eis-audit} 提供），缺失时运行时不装配（fail-fast）；</li>
 *   <li>处理器 / 规则 / 授权钩子经 SPIs 收集（应用按域注册；授权钩子缺失 =
 *       全部放行——<b>生产必须配置</b>，见 README 安全边界）；</li>
 *   <li>端点仅 Servlet Web 环境注册；操作人解析在存在 Spring Security 时缺省接
 *       安全上下文；</li>
 *   <li>整体开关：{@code byteforce.eis.core.enabled=false}。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "byteforce.eis.core", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(EisCoreProperties.class)
public class EisCoreAutoConfiguration {

    /**
     * EIS 时钟（系统时钟的单调包装；应用可覆盖为受控时钟）。
     *
     * @return EIS 时钟
     */
    @Bean
    @ConditionalOnMissingBean
    public EisClock eisClock() {
        return EisClock.monotonic(EisClock.system());
    }

    /**
     * 对象类型注册表（收集应用注册的 {@link EisObjectType} Bean）。
     *
     * @param types 对象类型 Bean
     * @return 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public EisObjectTypeRegistry eisObjectTypeRegistry(ObjectProvider<EisObjectType> types) {
        return new EisObjectTypeRegistry(types.orderedStream().toList());
    }

    /**
     * 双时间轴对象存储。
     *
     * @param jdbcTemplate JDBC 模板
     * @param registry     对象类型注册表
     * @return 对象存储
     */
    @Bean
    @ConditionalOnMissingBean
    public EisObjectRepository eisObjectRepository(JdbcTemplate jdbcTemplate, EisObjectTypeRegistry registry) {
        return new EisObjectRepository(jdbcTemplate, registry);
    }

    /**
     * 命令存储。
     *
     * @param jdbcTemplate JDBC 模板
     * @return 命令存储
     */
    @Bean
    @ConditionalOnMissingBean
    public EisCommandStore eisCommandStore(JdbcTemplate jdbcTemplate) {
        return new EisCommandStore(jdbcTemplate);
    }

    /**
     * 事件与 outbox 存储。
     *
     * @param jdbcTemplate JDBC 模板
     * @return 事件存储
     */
    @Bean
    @ConditionalOnMissingBean
    public EisEventStore eisEventStore(JdbcTemplate jdbcTemplate) {
        return new EisEventStore(jdbcTemplate);
    }

    /**
     * 失效/唤醒钩子缺省实现（空操作；fail-open）。
     *
     * @return 空操作钩子
     */
    @Bean
    @ConditionalOnMissingBean
    public EisNotifyHook eisNotifyHook() {
        return events -> {
            // 缺省不动作：缓存失效/唤醒由应用按 §3.4 T2 接线（coord Event / TTL 兜底）
        };
    }

    /**
     * outbox 分发器缺省实现（进程内）。
     *
     * @return 进程内分发器
     */
    @Bean
    @ConditionalOnMissingBean
    public EisOutboxDispatcher eisOutboxDispatcher() {
        return new InProcessEisOutboxDispatcher();
    }

    /**
     * 命令运行时（写路径唯一入口）。
     *
     * @param clock              EIS 时钟
     * @param registry           对象类型注册表
     * @param objects            对象存储
     * @param commands           命令存储
     * @param events             事件存储
     * @param auditAppender      审计追加器（必配）
     * @param authorizer         授权钩子（可空）
     * @param handlers           命令处理器
     * @param rules              规则链
     * @param dispatcher         outbox 分发器
     * @param notifyHook         失效/唤醒钩子
     * @param transactionManager 事务管理器
     * @return 命令运行时
     */
    @Bean
    @ConditionalOnMissingBean
    public EisCommandRuntime eisCommandRuntime(EisClock clock, EisObjectTypeRegistry registry,
                                               EisObjectRepository objects, EisCommandStore commands,
                                               EisEventStore events, EisAuditAppender auditAppender,
                                               ObjectProvider<EisAuthorizer> authorizer,
                                               ObjectProvider<EisCommandHandler> handlers,
                                               ObjectProvider<EisRule> rules,
                                               EisOutboxDispatcher dispatcher, EisNotifyHook notifyHook,
                                               PlatformTransactionManager transactionManager) {
        return new EisCommandRuntime(clock, registry, objects, commands, events, auditAppender,
                authorizer.getIfAvailable(), handlers.orderedStream().toList(), rules.orderedStream().toList(),
                dispatcher, notifyHook, transactionManager);
    }

    /**
     * Web 面（Servlet 环境）：命令端点与端点级错误映射。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(DispatcherServlet.class)
    static class WebConfiguration {

        /**
         * 命令端点。操作人解析缺失时退化为一律未认证（fail-closed 401）。
         *
         * @param runtime  命令运行时
         * @param resolver 操作人解析（可空）
         * @return 命令端点
         */
        @Bean
        @ConditionalOnMissingBean
        EisCommandController eisCommandController(EisCommandRuntime runtime,
                                                  ObjectProvider<EisActorResolver> resolver) {
            EisActorResolver actorResolver = resolver.getIfAvailable(
                    () -> () -> Optional.<UUID>empty());
            return new EisCommandController(runtime, actorResolver);
        }

        /**
         * 端点级异常映射（EIS 错误体）。
         *
         * @return 异常处理器
         */
        @Bean
        @ConditionalOnMissingBean
        EisCommandExceptionHandler eisCommandExceptionHandler() {
            return new EisCommandExceptionHandler();
        }
    }

    /**
     * 操作人解析缺省实现（Spring Security 在 classpath 时生效）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SecurityContextHolder.class)
    static class SecurityActorResolverConfiguration {

        /**
         * 安全上下文操作人解析。
         *
         * @return 操作人解析
         */
        @Bean
        @ConditionalOnMissingBean
        EisActorResolver securityContextEisActorResolver() {
            return new SecurityContextEisActorResolver();
        }
    }
}
