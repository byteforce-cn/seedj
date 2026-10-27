package cn.byteforce.seedj.starter.eis.domain;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.starter.eis.domain.internal.EventTypeRegistry;
import cn.byteforce.seedj.starter.eis.domain.jackson.JacksonEventPayloadCodec;
import cn.byteforce.seedj.starter.eis.domain.jdbc.PostgresEventLogStore;
import cn.byteforce.seedj.starter.eis.domain.spi.EventPayloadCodec;
import cn.byteforce.seedj.starter.eis.domain.spi.EventUpcasterRegistry;
import cn.byteforce.seedj.starter.eis.domain.spi.TenantResolver;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * 事件脊柱自动配置：类型注册表、upcaster 注册表、编解码器、租户解析与 PostgreSQL 事件日志。
 *
 * <p>全部 Bean 均可被应用以同类型覆盖；事件载荷使用<b>专用</b> {@link JsonMapper}
 * （不注入 Web 层 ObjectMapper），避免 {@code starter-json} 的 {@code Long} 字符串化
 * 改变 payload 形态。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "byteforce.eis.domain", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(EisDomainProperties.class)
public class EisDomainAutoConfiguration {

    /**
     * 事件类型注册表。
     *
     * @return 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public EventTypeRegistry eisDomainEventTypeRegistry() {
        return new EventTypeRegistry();
    }

    /**
     * upcaster 注册表。
     *
     * @return 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public EventUpcasterRegistry eisDomainEventUpcasterRegistry() {
        return new EventUpcasterRegistry();
    }

    /**
     * 事件 payload 编解码器（专用 JsonMapper）。
     *
     * @param eventTypes 事件类型注册表
     * @param upcasters  upcaster 注册表
     * @return 编解码器
     */
    @Bean
    @ConditionalOnMissingBean
    public EventPayloadCodec eisDomainEventPayloadCodec(EventTypeRegistry eventTypes,
                                                        EventUpcasterRegistry upcasters) {
        return new JacksonEventPayloadCodec(JsonMapper.builder().build(), eventTypes, upcasters);
    }

    /**
     * 默认租户解析：读 {@code RequestContext}。
     *
     * @return 租户解析
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantResolver eisDomainTenantResolver() {
        return RequestContext::tenantId;
    }

    /**
     * PostgreSQL 事件日志。
     *
     * @param jdbc      JDBC 模板
     * @param codec     编解码器
     * @param eventTypes 事件类型注册表
     * @param resolver  租户解析
     * @param properties 配置属性
     * @return 事件日志
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    public EventLogStore eisDomainEventLogStore(JdbcTemplate jdbc, EventPayloadCodec codec,
                                                EventTypeRegistry eventTypes, TenantResolver resolver,
                                                EisDomainProperties properties) {
        return new PostgresEventLogStore(jdbc, codec, eventTypes, resolver,
                properties.isTenantRequired());
    }
}
