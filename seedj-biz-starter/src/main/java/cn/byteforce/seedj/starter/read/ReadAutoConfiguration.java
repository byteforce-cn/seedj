package cn.byteforce.seedj.starter.read;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.core.security.ContextResolver;
import cn.byteforce.seedj.core.security.ContextWriter;
import cn.byteforce.seedj.core.security.DefaultContextResolver;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.read.DataScopeResolver;
import cn.byteforce.seedj.read.FieldPolicyResolver;
import cn.byteforce.seedj.read.QueryExecutor;
import cn.byteforce.seedj.read.ReadAuditSink;
import cn.byteforce.seedj.read.ReadGateway;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.starter.cqrs.wiring.TemporalCommandAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * 读路径装配：网关、审计、请求事实过滤器与通用查询面开关。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(after = TemporalCommandAutoConfiguration.class, afterName =
        "cn.byteforce.seedj.starter.read.ObjectModuleAutoConfiguration")
@ConditionalOnBean({TemporalStore.class, ObjectModuleRegistry.class})
public class ReadAutoConfiguration {

    /**
     * 默认审计出口：结构化日志 + Micrometer 计数；业务可覆盖为持久化审计。
     *
     * @param meters 可选指标注册表
     * @return 审计出口
     */
    @Bean
    @ConditionalOnMissingBean
    public ReadAuditSink readAuditSink(ObjectProvider<MeterRegistry> meters) {
        return new LoggingReadAuditSink(meters.getIfAvailable());
    }

    /**
     * 默认请求事实解析器。
     *
     * @param writers 业务写入扩展点
     * @return 解析器
     */
    @Bean
    @ConditionalOnMissingBean
    public ContextResolver contextResolver(ObjectProvider<ContextWriter> writers) {
        return new DefaultContextResolver(writers.orderedStream().toList());
    }

    /**
     * 唯一读入口。
     *
     * @param registry 模块注册表
     * @param store    对象存储
     * @param scopes   行范围贡献者
     * @param fields   字段策略贡献者
     * @param audit    审计出口
     * @param clock    取时入口
     * @param ids      事件 id 生成器
     * @return 网关
     */
    @Bean
    @ConditionalOnMissingBean
    public ReadGateway readGateway(ObjectModuleRegistry registry, TemporalStore store,
                                   ObjectProvider<DataScopeResolver> scopes,
                                   ObjectProvider<FieldPolicyResolver> fields,
                                   ReadAuditSink audit, ObjectProvider<Clock> clock,
                                   ObjectProvider<IdGenerator<UUID>> ids,
                                   ObjectProvider<QueryExecutor> executor,
                                   AuthorizationDecisionPoint decisions) {
        return new ReadGateway(registry, store, scopes.orderedStream().toList(),
                fields.orderedStream().toList(), audit,
                clock.getIfAvailable(Clocks::system),
                ids.getIfAvailable(() -> UUID::randomUUID),
                executor.getIfAvailable(), decisions);
    }

    /**
     * 自定义查询执行器：只在有 JDBC 数据源时注册（PostgreSQL）。
     *
     * @param jdbc JDBC 模板
     * @return 执行器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    public QueryExecutor queryExecutor(JdbcTemplate jdbc) {
        return new cn.byteforce.seedj.infra.jdbc.read.PostgresQueryExecutor(jdbc);
    }

    /**
     * 请求事实过滤器：认证之后打开、finally 关闭。
     *
     * @param resolver 事实解析器
     * @param callers  当前调用方
     * @return 过滤器
     */
    @Bean
    @ConditionalOnBean(CallerProvider.class)
    @ConditionalOnMissingBean
    public ScopeContextFilter scopeContextFilter(ContextResolver resolver,
                                                 CallerProvider callers) {
        return new ScopeContextFilter(resolver, callers);
    }

    /**
     * 通用 {@code /objects/**} 面：默认关闭，显式 {@code seedj.read.generic-surface=enabled}
     * 才注册。
     *
     * @param gateway 网关
     * @param callers 当前调用方
     * @return 控制器
     */
    @Bean
    @ConditionalOnBean(CallerProvider.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.read", name = "generic-surface",
            havingValue = "enabled")
    public cn.byteforce.seedj.starter.domain.web.ObjectQueryController objectQueryController(
            ReadGateway gateway, CallerProvider callers) {
        return new cn.byteforce.seedj.starter.domain.web.ObjectQueryController(gateway, callers);
    }
}
