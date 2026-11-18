package cn.byteforce.seedj.starter.ai.autoconfigure;

import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.guard.ConfiguredToolRegistry;
import cn.byteforce.seedj.starter.ai.plane.CapabilityRegistry;
import cn.byteforce.seedj.starter.ai.plane.DecisionController;
import cn.byteforce.seedj.starter.ai.plane.DecisionService;
import cn.byteforce.seedj.starter.ai.plane.JwksController;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 决策面装配：能力集、决策服务、{@code /v1/decisions} 与 JWKS 端点。
 *
 * <p>门控是四层的：{@link AiEnabledMarker}（应用写了 {@code @EnableAIServer}）、{@code seedj.ai.plane.enabled}
 * （<b>默认 false</b>——执行侧不需要决策面）、servlet web 上下文。登记进 {@code AutoConfiguration.imports} 是安全的：
 * 没有 AI 启用标记的宿主（IAM / IM / HR 等）一个决策面 bean 都拿不到，也不会多出两个 HTTP 端点。
 *
 * <p><b>与 guard 的 bean 复用</b>：{@link ToolRegistry} 与 {@link DecisionTokenIssuer} 在两侧都提供，靠
 * {@code @ConditionalOnMissingBean} 保证单例——只开决策面（不开 guard）的进程同样有工具注册表与签发方；两侧同进程时
 * 决策面复用 guard 已经建好的那两个 bean，签发的公钥与守卫验签用的公钥因此必然一致。
 *
 * <p><b>启动期校验</b>在 {@link DecisionService} 的构造器里：{@code seedj.ai.plane.tenant}、
 * {@code seedj.ai.identity.actor}、{@code seedj.ai.identity.on-behalf-of} 必须存在且是 UUID，任一缺失即启动失败并
 * 点名配置键。租户没有运行时来源（T0 无租户上下文注入），只能由配置钉死；身份同理——决策面不接受请求自称的主体。
 * {@code seedj.ai.decision.audience} 不做启动期强制：请求可以逐条给受众，两者都缺时那条请求 400。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnBean(AiEnabledMarker.class)
@ConditionalOnProperty(prefix = "seedj.ai.plane", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AiProperties.class)
public class DecisionPlaneAutoConfiguration {

    /**
     * 工具注册表：{@code seedj.ai.tools[]} 的唯一读入口（与 guard 的同名 bean 二选一）。
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
     * DecisionToken 签发方：决策面是签发方，JWKS 端点发布它的公钥。
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
     * 任务契约能力集：启动期与工具注册表交叉校验。
     *
     * @param properties AI 配置
     * @param registry 工具注册表
     * @return 能力集
     */
    @Bean
    @ConditionalOnMissingBean
    public CapabilityRegistry capabilityRegistry(AiProperties properties, ToolRegistry registry) {
        return new CapabilityRegistry(properties.contracts(), registry);
    }

    /**
     * 决策服务：判定契约能力、先落 intent、再决定签不签 token。
     *
     * @param capabilities 能力集
     * @param registry 工具注册表
     * @param ledger 决策账本（由账本装配或宿主提供）
     * @param issuer 签发方
     * @param clock 取时入口
     * @param properties AI 配置
     * @return 决策服务
     */
    @Bean
    @ConditionalOnMissingBean
    public DecisionService decisionService(
            CapabilityRegistry capabilities,
            ToolRegistry registry,
            DecisionLedger ledger,
            DecisionTokenIssuer issuer,
            Clock clock,
            AiProperties properties) {
        return new DecisionService(capabilities, registry, ledger, issuer, clock, properties);
    }

    /**
     * 决策 HTTP 端点。
     *
     * @param decisions 决策服务
     * @return 控制器
     */
    @Bean
    @ConditionalOnMissingBean
    public DecisionController decisionController(DecisionService decisions) {
        return new DecisionController(decisions);
    }

    /**
     * 决策签名公钥端点。
     *
     * @param issuer 签发方
     * @return 控制器
     */
    @Bean
    @ConditionalOnMissingBean
    public JwksController jwksController(DecisionTokenIssuer issuer) {
        return new JwksController(issuer);
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
