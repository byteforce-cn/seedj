package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.IamEnabledMarker;
import cn.byteforce.seedj.iam.authz.IamBindingStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 授权绑定的 PostgreSQL 选择：{@code seedj.authz.provider} 缺省时跟随 {@code seedj.iam.store=jdbc}。
 *
 * <p>与内存适配器的条件是互补的，因此两个适配器同时在 classpath 时只会有一个绑定存储。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ConditionalOnBean({IamEnabledMarker.class, JdbcTemplate.class})
@ConditionalOnProperty(
        prefix = "seedj.authz",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnClass({JdbcTemplate.class})
public class PostgresIamAuthzAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IamBindingStore.class)
    @Conditional(PostgresBindingProviderCondition.class)
    IamBindingStore iamBindingStore(JdbcTemplate jdbc) {
        return new JdbcIamBindingStore(jdbc);
    }

    /**
     * {@code seedj.authz.provider} 显式值优先；缺省时 {@code seedj.iam.store=jdbc} 选 PostgreSQL。
     *
     * <p>{@code seedj.iam.enabled} 与 marker 由类级条件负责，这里只看 provider 的选择语义。
     */
    static final class PostgresBindingProviderCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String provider = context.getEnvironment().getProperty("seedj.authz.provider");
            if (provider != null && !provider.isBlank()) {
                return "postgres".equals(provider);
            }
            return "jdbc".equals(context.getEnvironment().getProperty("seedj.iam.store", "memory"));
        }
    }
}
