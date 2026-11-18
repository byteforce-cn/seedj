package cn.byteforce.seedj.starter.ai.autoconfigure;

import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.guard.ConfiguredToolRegistry;
import cn.byteforce.seedj.starter.ai.plane.CapabilityRegistry;
import cn.byteforce.seedj.starter.ai.plane.ConfiguredReconciliationSource;
import cn.byteforce.seedj.starter.ai.plane.DecisionController;
import cn.byteforce.seedj.starter.ai.plane.DecisionService;
import cn.byteforce.seedj.starter.ai.plane.JwksController;
import cn.byteforce.seedj.starter.ai.plane.OutcomeDeriver;
import cn.byteforce.seedj.starter.ai.plane.PlaneReconciliationSource;
import cn.byteforce.seedj.starter.ai.plane.ReconciliationJob;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * <p><b>outcome 上报与对账是可选的</b>：{@link OutcomeDeriver} 与 {@link ReconciliationJob} 只在能找到对账数据源时
 * 装配——{@code seedj.ai.plane.reconcile.datasource} 非空就按 bean 名取，空就用主 {@link DataSource}；两者都没有时
 * 一个也不装配（上报端点 503）并 WARN 一次，决策面本身不受影响。定时对账还要再加一道
 * {@code seedj.ai.plane.reconcile.enabled=true}（默认 false），且宿主需自己写 {@code @EnableScheduling}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnBean(AiEnabledMarker.class)
@ConditionalOnProperty(prefix = "seedj.ai.plane", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AiProperties.class)
public class DecisionPlaneAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DecisionPlaneAutoConfiguration.class);

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
     * <p>{@code decision_id} 优先用宿主已有的 {@link IdGenerator}{@code <UUID>}（biz 的
     * {@code commandIdGenerator} 是 UUIDv7，时间有序）；宿主没有时退化为 {@code UUID.randomUUID()}。
     *
     * @param capabilities 能力集
     * @param registry 工具注册表
     * @param ledger 决策账本（由账本装配或宿主提供）
     * @param issuer 签发方
     * @param idGenerators 宿主的标识生成器；缺失时用随机 UUID
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
            ObjectProvider<IdGenerator<UUID>> idGenerators,
            Clock clock,
            AiProperties properties) {
        return new DecisionService(
                capabilities,
                registry,
                ledger,
                issuer,
                idGenerators.getIfAvailable(() -> UUID::randomUUID),
                clock,
                properties);
    }

    /**
     * 决策 HTTP 端点。
     *
     * @param decisions 决策服务
     * @param outcomes outcome 派生器；没有对账数据源时为空，上报端点回 503
     * @return 控制器
     */
    @Bean
    @ConditionalOnMissingBean
    public DecisionController decisionController(
            DecisionService decisions, ObjectProvider<OutcomeDeriver> outcomes) {
        return new DecisionController(decisions, outcomes.getIfAvailable());
    }

    /**
     * outcome 派生器：把 runtime 上报变成一条 outcome。只在找得到对账数据源时装配。
     *
     * @param properties AI 配置
     * @param ledger 决策账本
     * @param dataSources 宿主的数据源；{@code reconcile.datasource} 为空时用主数据源
     * @param context 按 bean 名取具名对账数据源
     * @return outcome 派生器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    public OutcomeDeriver outcomeDeriver(
            AiProperties properties,
            DecisionLedger ledger,
            ObjectProvider<DataSource> dataSources,
            ApplicationContext context) {
        return new OutcomeDeriver(
                reconcileDataSource(properties, context, dataSources), ledger, properties);
    }

    /**
     * 待对账决策的来源：T0 默认实现返回空列表，见 {@link ConfiguredReconciliationSource}。
     *
     * @return 对账来源
     */
    @Bean
    @ConditionalOnMissingBean
    public PlaneReconciliationSource planeReconciliationSource() {
        return new ConfiguredReconciliationSource();
    }

    /**
     * 对账作业：按已知 decision_id 补链校验。只在找得到对账数据源时装配。
     *
     * @param properties AI 配置
     * @param ledger 决策账本
     * @param source 待对账决策的来源
     * @param dataSources 宿主的数据源；{@code reconcile.datasource} 为空时用主数据源
     * @param context 按 bean 名取具名对账数据源
     * @return 对账作业
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    public ReconciliationJob reconciliationJob(
            AiProperties properties,
            DecisionLedger ledger,
            PlaneReconciliationSource source,
            ObjectProvider<DataSource> dataSources,
            ApplicationContext context) {
        return new ReconciliationJob(
                reconcileDataSource(properties, context, dataSources), ledger, source, properties);
    }

    /**
     * 定时对账包装：只做按固定延迟驱动一次对账，受 {@code seedj.ai.plane.reconcile.enabled} 门控（默认 false）。
     *
     * @param job 对账作业
     * @param clock 取时入口
     * @return 定时入口
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReconciliationJob.class)
    @ConditionalOnProperty(
            prefix = "seedj.ai.plane.reconcile",
            name = "enabled",
            havingValue = "true")
    public ReconciliationJob.Scheduler planeReconcileScheduler(
            ReconciliationJob job, Clock clock) {
        return new ReconciliationJob.Scheduler(job, clock);
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

    /** 决策面打开但没有可用的对账数据源：不装配上报与对账，WARN 一次。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(DataSource.class)
    static class ReconcileWithoutDataSourceConfiguration {

        @Bean
        ApplicationRunner reconcileWithoutDataSourceWarning() {
            return args ->
                    log.warn(
                            "seedj.ai.plane.reconcile: 未找到对账数据源"
                                    + "（seedj.ai.plane.reconcile.datasource 为空且宿主没有主"
                                    + " DataSource），不装配 outcome 上报与对账");
        }
    }

    /**
     * 按配置取对账数据源：{@code seedj.ai.plane.reconcile.datasource} 非空时是 bean 名，空时用主数据源。
     *
     * <p>不新建连接池配置：对账只借宿主的业务库读事实，第二套数据源配置会让「同一业务库」多一种连接方式。
     * 宿主有多个数据源又没标 {@code @Primary} 时只能靠具名配置，所以本方法拒绝猜。
     */
    private static DataSource reconcileDataSource(
            AiProperties properties,
            ApplicationContext context,
            ObjectProvider<DataSource> dataSources) {
        String name = properties.plane().reconcile().datasource();
        if (name != null && !name.isBlank()) {
            return context.getBean(name, DataSource.class);
        }
        DataSource primary = dataSources.getIfUnique();
        if (primary == null) {
            throw new IllegalStateException(
                    "对账数据源不唯一也没有主数据源：请用 seedj.ai.plane.reconcile.datasource 指定 bean 名");
        }
        return primary;
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
