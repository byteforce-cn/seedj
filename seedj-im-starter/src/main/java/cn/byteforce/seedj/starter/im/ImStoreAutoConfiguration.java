package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.im.ImEnabledMarker;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * IM 启停与基础设施：存储实现由适配器自带自动配置提供。
 *
 * <p>{@code seedj.im.store} 的选择在 {@code seedj-infra-local}（memory）与 {@code
 * seedj-jdbc-postgresql}（jdbc）各自的自动配置里；本类只保留与存储选型无关的时钟和"显式 jdbc 缺数据源必须失败"的前置检查。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnBean(ImEnabledMarker.class)
@ConditionalOnProperty(
        prefix = "seedj.im",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ImStoreAutoConfiguration {

    /**
     * IM 取时入口；容器里已有 {@code Clock} 时直接复用（业务命令的契约时钟）。
     *
     * @return 时钟
     */
    @Bean
    @ConditionalOnMissingBean
    Clock imClock() {
        return Clock.systemUTC();
    }

    /**
     * {@code seedj.im.store=jdbc} 但缺数据源：显式失败。
     *
     * <p>不静默退回内存——那样消息在重启后消失，而运维看不出任何异常。
     *
     * @return 前置检查
     */
    @Bean
    @ConditionalOnProperty(prefix = "seedj.im", name = "store", havingValue = "jdbc")
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor
            imJdbcStorePrecondition() {
        return beanFactory -> {
            if (beanFactory.getBeanNamesForType(javax.sql.DataSource.class).length == 0) {
                throw new IllegalStateException(
                        "seedj.im.store=jdbc requires a DataSource/JdbcTemplate; configure"
                                + " spring.datasource or set seedj.im.store=memory");
            }
        };
    }
}
