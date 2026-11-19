package cn.byteforce.seedj.starter.ai.autoconfigure;

import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.spi.DecisionAdmissionStore;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.CallerIdCodec;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionAdmissionStore;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.guard.AdmissionSweeper;
import cn.byteforce.seedj.starter.ai.guard.AgentDecisionGuc;
import cn.byteforce.seedj.starter.ai.guard.AgentDecisionTransactionRunner;
import cn.byteforce.seedj.starter.ai.guard.AgentGuardFilter;
import cn.byteforce.seedj.starter.ai.guard.ConfiguredToolRegistry;
import cn.byteforce.seedj.starter.ai.policy.ConfiguredPolicyHeadSource;
import cn.byteforce.seedj.starter.ai.policy.PolicyHeadSource;
import cn.byteforce.seedj.starter.ai.policy.PolicyProperties;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenVerifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 执行侧 guard 装配：工具注册表、DecisionToken 签发/校验与命令入口过滤器。
 *
 * <p>门控是三层的：{@link AiEnabledMarker}（应用写了 {@code @EnableAIServer}）、{@code seedj.ai.guard.enabled}
 * （默认 true——执行侧默认拒绝无 token 的写命令）、servlet web 上下文。没有 web 上下文时命令入口不存在，只装配工具 注册表与 token 组件。
 *
 * <p>{@link DecisionTokenIssuer} 在 guard 侧也提供（{@code @ConditionalOnMissingBean}）：T0 里决策面与执行侧同进程，
 * 守卫用同一个 issuer 发布的公钥验签；分角色部署时守卫需要的是公钥，未来换 JWKS 拉取（T0 不做）。
 *
 * <p><b>{@code decision.audience} 在 guard 侧是启动期必填</b>：它是本服务名的唯一来源，决定 token 的 {@code aud}
 * 比什么。没有它就只能拿请求头（{@code Host} / server name）猜，而请求头是攻击者可控的——换个 {@code Host} 就能把 发给 A 服务的 token 重放到 B
 * 服务。所以 {@code guard.enabled=true} 且受众为空白时本配置直接让启动失败。
 *
 * <p>登记进 {@code AutoConfiguration.imports} 是安全的：没有 AI 启用标记的宿主（IAM / IM / HR 等）不会拿到任何 guard
 * bean，命令端点也不会被误拦。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnBean(AiEnabledMarker.class)
@ConditionalOnProperty(
        prefix = "seedj.ai.guard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties({
    AiProperties.class,
    PolicyProperties.class,
    AdmissionProperties.class
})
public class AiGuardAutoConfiguration {

    /**
     * 启动期校验 guard 侧的必需配置。
     *
     * <p>放在构造器里而不是某个 bean 方法里：无论宿主是否覆盖了过滤器或校验器，缺受众都必须起不来。
     *
     * @param properties AI 配置
     * @throws IllegalStateException {@code seedj.ai.decision.audience} 为空白
     */
    public AiGuardAutoConfiguration(AiProperties properties) {
        String audience = properties.decision().audience();
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException(
                    "seedj.ai.decision.audience 未配置：guard 用它判定 token 的 aud"
                            + "（防跨服务重放），不能从请求头推断；只当决策面时请把"
                            + " seedj.ai.guard.enabled 设成 false");
        }
    }

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
    /**
     * 无 DataSource 的内存准入存储；有 DataSource 时由 PostgreSQL 配置提供。
     *
     * @return 准入存储
     */
    @Bean
    @ConditionalOnMissingBean({DecisionAdmissionStore.class, DataSource.class})
    public DecisionAdmissionStore inMemoryDecisionAdmissionStore() {
        return new InMemoryDecisionAdmissionStore();
    }

    /**
     * 命令事务的 GUC 设置器；只有有 DataSource 且准入开启时才装配。
     *
     * @param dataSource 数据源
     * @return GUC 设置器
     */
    @Bean(autowireCandidate = false)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(
            prefix = "seedj.ai.guard.admission",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public AgentDecisionGuc agentDecisionGuc(DataSource dataSource) {
        return new AgentDecisionGuc(new JdbcTemplate(dataSource));
    }

    /**
     * 把宿主的 {@link TransactionRunner} 包一层，在事务内设置 RLS GUC。
     *
     * @param guc GUC 设置器
     * @return bean 后处理器
     */
    @Bean
    static BeanPostProcessor agentDecisionTransactionRunnerPostProcessor(
            ObjectProvider<AgentDecisionGuc> guc) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                AgentDecisionGuc available = guc.getIfAvailable();
                if (bean instanceof TransactionRunner runner
                        && !(bean instanceof AgentDecisionTransactionRunner)
                        && available != null) {
                    return new AgentDecisionTransactionRunner(runner, available);
                }
                return bean;
            }
        };
    }

    /**
     * 过期 admission 清理；宿主需要 {@code @EnableScheduling}。
     *
     * @param store 准入存储
     * @param properties 准入配置
     * @return 清理任务
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DecisionAdmissionStore.class)
    public AdmissionSweeper admissionSweeper(
            DecisionAdmissionStore store, AdmissionProperties properties) {
        return new AdmissionSweeper(store, Duration.ofHours(1));
    }

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
     * @param heads 策略 head 源；未启用策略时为空，校验器退回 {@link PolicyHeadSource#none()}
     * @param properties AI 配置；{@code decision.audience} 已由构造器保证非空
     * @return 校验器
     */
    @Bean
    @ConditionalOnMissingBean
    public DecisionTokenVerifier decisionTokenVerifier(
            DecisionTokenIssuer issuer,
            ToolRegistry registry,
            ObjectProvider<PolicyHeadSource> heads,
            AiProperties properties) {
        return new DecisionTokenVerifier(
                Map.of(issuer.kid(), issuer.jwks().publicKey()),
                registry,
                properties.decision().audience(),
                heads.getIfAvailable(PolicyHeadSource::none));
    }

    /**
     * 策略 head 源：{@code seedj.ai.policy.enabled=true} 且 {@code verify=irreversible} 时读配置。
     *
     * <p>{@code verify=none} 返回 {@link PolicyHeadSource#none()}——bean 存在但不参与校验，这样「只钉版本、
     * 先不强制」不需要宿主自己造 bean；宿主的 {@link PolicyHeadSource} 仍然优先（{@code @ConditionalOnMissingBean}）。
     *
     * @param properties 策略配置
     * @return head 源
     * @throws IllegalStateException 启用时缺 {@code bundle} 或 {@code version}
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.ai.policy", name = "enabled", havingValue = "true")
    public PolicyHeadSource policyHeadSource(PolicyProperties properties) {
        return properties.verify() == PolicyProperties.Verify.NONE
                ? PolicyHeadSource.none()
                : new ConfiguredPolicyHeadSource(properties);
    }

    /** 过滤器与它的注册只对 servlet web 应用有意义：命令入口本身就是 HTTP。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class GuardWebConfiguration {

        /**
         * 命令入口守卫。
         *
         * <p>{@code CallerIdCodec} 与 {@code CommandController} 共用：缺省 {@code CallerIdCodec.uuid()}，
         * 名称型标识的部署必须让两侧拿到同一个 {@code nameUuid()} 实现。
         *
         * <p>{@link JsonMapper} 同样由宿主注入（缺省用默认构造）：宿主自定义了数字映射（例如把 JSON 数字读成 {@code
         * BigDecimal}）时，guard 解析命令体与决策面算 {@code args_hash} 必须是同一套 mapper，否则每次写命令都 403。
         *
         * @param verifier token 校验器
         * @param callers 当前调用方（由 biz 安全链填充）
         * @param callerIds 调用方标识 → actor 的编解码；缺省按 UUID 解析
         * @param clock 取时入口
         * @param mappers 宿主的 JSON 编解码；缺省用默认构造
         * @param properties AI 配置
         * @return 守卫过滤器
         */
        @Bean
        @ConditionalOnMissingBean
        public AgentGuardFilter agentGuardFilter(
                DecisionTokenVerifier verifier,
                CallerProvider callers,
                ObjectProvider<CallerIdCodec> callerIds,
                Clock clock,
                ObjectProvider<JsonMapper> mappers,
                ObjectProvider<DecisionAdmissionStore> admissions,
                AdmissionProperties admissionProperties,
                AiProperties properties) {
            CallerIdCodec codec = callerIds.getIfAvailable(CallerIdCodec::uuid);
            JsonMapper mapper = mappers.getIfAvailable(() -> JsonMapper.builder().build());
            return new AgentGuardFilter(
                    verifier,
                    callers,
                    codec,
                    properties.decision().audience(),
                    clock,
                    mapper,
                    admissions.getIfAvailable(),
                    admissionProperties.ttl());
        }

        /**
         * 把守卫挂在 {@code /commands/*} 上。
         *
         * <p>顺序取安全链之后的一档：安全链（{@code springSecurityFilterChain}，order = {@code
         * SecurityFilterProperties.DEFAULT_FILTER_ORDER} = -100）先把 bearer 认成 {@code Caller}，守卫再消费
         * {@code CallerProvider} 与 {@code x-agent-decision}；servlet filter 一律早于 {@code
         * DispatcherServlet}，所以仍在 {@code CommandController} 之前。跑在安全链之前会让调用方恒为空、 每个写命令都 401。
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
