package cn.byteforce.seedj.starter.data.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis-Plus 基线自动配置：审计字段填充、分页插件与租户插件。
 *
 * <ul>
 *   <li>审计填充：{@code byteforce.mybatis.audit.enabled=true}（默认）；</li>
 *   <li>分页：{@code byteforce.mybatis.pagination.enabled=true}（默认），
 *       单页上限 {@code byteforce.mybatis.pagination.max-limit=500}；</li>
 *   <li>租户：{@code byteforce.mybatis.tenant.enabled=true} 显式开启（默认关闭），
 *       上下文缺失租户时 fail-closed。</li>
 * </ul>
 *
 * <p>整体开关：{@code byteforce.mybatis.enabled=false}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass({MybatisPlusInterceptor.class, PaginationInnerInterceptor.class})
@ConditionalOnProperty(prefix = "byteforce.mybatis", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MybatisBaselineProperties.class)
public class MybatisBaselineAutoConfiguration {

    /**
     * 审计字段自动填充器。
     *
     * @param properties 基线属性
     * @return MetaObjectHandler
     */
    @Bean
    @ConditionalOnMissingBean(MetaObjectHandler.class)
    @ConditionalOnProperty(prefix = "byteforce.mybatis.audit", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public MetaObjectHandler auditMetaObjectHandler(MybatisBaselineProperties properties) {
        return new AuditMetaObjectHandler(properties.getAudit().isUseRequestContext());
    }

    /**
     * MyBatis-Plus 内联插件装配（租户在前、分页在后，符合插件执行顺序要求）。
     *
     * @param properties 基线属性
     * @return MybatisPlusInterceptor
     */
    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    public MybatisPlusInterceptor mybatisPlusInterceptor(MybatisBaselineProperties properties) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        if (properties.getTenant().isEnabled()) {
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new RequestContextTenantLineHandler(
                            properties.getTenant().getColumn(),
                            properties.getTenant().getIgnoreTables())));
        }
        if (properties.getPagination().isEnabled()) {
            PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
            pagination.setMaxLimit(properties.getPagination().getMaxLimit());
            interceptor.addInnerInterceptor(pagination);
        }
        return interceptor;
    }
}
