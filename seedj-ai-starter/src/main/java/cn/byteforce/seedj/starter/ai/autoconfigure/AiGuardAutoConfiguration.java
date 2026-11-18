package cn.byteforce.seedj.starter.ai.autoconfigure;

import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.guard.AgentGuardFilter;
import cn.byteforce.seedj.starter.ai.guard.ConfiguredToolRegistry;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 执行侧 guard 装配：工具注册表、DecisionToken 签发/校验与命令入口过滤器。
 *
 * <p>门控是三层的：{@link AiEnabledMarker}（应用写了 {@code @EnableAIServer}）、{@code seedj.ai.guard.enabled}
 * （默认 true——执行侧默认拒绝无 token 的写命令）、servlet web 上下文。没有 web 上下文时命令入口不存在，只装配工具注册表与
 * token 组件。
 *
 * <p>{@link DecisionTokenIssuer} 在 guard 侧也提供（{@code @ConditionalOnMissingBean}）：T0 里决策面与执行侧同进程，
 * 守卫用同一个 issuer 发布的公钥验签；分角色部署时守卫需要的是公钥，未来换 JWKS 拉取（T0 不做）。
 *
 * <p>登记进 {@code AutoConfiguration.imports} 是安全的：没有 AI 启用标记的宿主（IAM / IM / HR 等）不会拿到任何 guard
 * bean，命令端点也不会被误拦。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnBean(AiEnabledMarker.class)
@ConditionalOnProperty(
        prefix = "seedj.ai.guard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(AiProperties.class)
public class AiGuardAutoConfiguration {

    /**
     * 工具注册表：{@code seedj.ai.tools[]} 的唯一读入口，启动期与命令目录交叉校验。
     *
     * @param properties AI 配置
     * @param catalogs 宿主的命令目录；没有时跳过交叉校验并 WARN
     * @return 工具注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(
            AiProperties properties, ObjectProvider<CommandCatalog> catalogs) {
        List<AgentToolSpec> tools = toolSpecs(properties.tools());
        CommandCatalog catalog = catalogs.getIfAvailable();
        return catalog == null
                ? new ConfiguredToolRegistry(tools)
                : new ConfiguredToolRegistry(tools, catalog);
    }

    /**
     * DecisionToken 签发方：guard 侧只用它取公钥（同进程部署），决策面复用同一个 bean。
     *
     * @param properties AI 配置
     * @return 签发方
     */
    @Bean
    @ConditionalOnMissingBean
    public DecisionTokenIssuer decisionTokenIssuer(AiProperties properties) {
        return new DecisionTokenIssuer(properties.decision());
    }

    /**
     * DecisionToken 校验器：公钥来自签发方的 JWKS，键为 {@code kid}。
     *
     * @param issuer 签发方（公钥来源）
     * @param registry 工具注册表
     * @param properties AI 配置；{@code decision.audience} 为空时按请求的 server name 判定
     * @return 校验器
     */
    @Bean
    @ConditionalOnMissingBean
    public DecisionTokenVerifier decisionTokenVerifier(
            DecisionTokenIssuer issuer, ToolRegistry registry, AiProperties properties) {
        return new DecisionTokenVerifier(
                Map.of(issuer.kid(), issuer.jwks().publicKey()),
                registry,
                properties.decision().audience());
    }

    /** 过滤器与它的注册只对 servlet web 应用有意义：命令入口本身就是 HTTP。 */    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class GuardWebConfiguration {

        /**
         * 命令入口守卫。
         *
         * @param verifier token 校验器
         * @param callers 当前调用方（由 biz 安全链填充）
         * @param clock 取时入口
         * @param properties AI 配置
         * @return 守卫过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        public AgentGuardFilter agentGuardFilter(
                DecisionTokenVerifier verifier,
                CallerProvider callers,
                Clock clock,
                AiProperties properties) {
            return new AgentGuardFilter(
                    verifier, callers, properties.decision().audience(), clock);
        }

        /**
         * 把守卫挂在 {@code /commands/*} 上。
         *
         * <p>顺序取安全链之后的一档：安全链（{@code springSecurityFilterChain}，order = {@code
         * SecurityFilterProperties.DEFAULT_FILTER_ORDER} = -100）先把 bearer 认成 {@code Caller}，守卫再消费
         * {@code CallerProvider} 与 {@code x-agent-decision}；servlet filter 一律早于 {@code DispatcherServlet}，
         * 所以仍在 {@code CommandController} 之前。跑在安全链之前会让调用方恒为空、每个写命令都 401。
         *
         * @param filter 守卫过滤器
         * @return 注册项
         */
        @Bean
        public FilterRegistrationBean<AgentGuardFilter> agentGuardFilterRegistration(
                AgentGuardFilter filter) {
            FilterRegistrationBean<AgentGuardFilter> registration =
                    new FilterRegistrationBean<>(filter);
            registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10);
            registration.setUrlPatterns(List.of("/commands/*"));
            return registration;
        }
    }

    /** 把 {@code seedj.ai.tools[]} 的配置项落成契约类型，并点名缺字段的条目。 */
    private static List<AgentToolSpec> toolSpecs(List<AiProperties.Tool> tools) {
        List<AgentToolSpec> specs = new ArrayList<>(tools.size());
        for (AiProperties.Tool tool : tools) {
            if (tool == null || tool.commandType() == null || tool.commandType().isBlank()) {
                throw new IllegalStateException("seedj.ai.tools 存在缺少 command-type 的条目");
            }
            if (tool.actionClass() == null) {
                throw new IllegalStateException(
                        "seedj.ai.tools 的 " + tool.commandType() + " 缺少 action-class");
            }
            specs.add(new AgentToolSpec(tool.commandType(), tool.actionClass()));
        }
        return List.copyOf(specs);
    }
}
