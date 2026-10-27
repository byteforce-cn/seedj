package cn.byteforce.seedj.starter.eis.domain;

import cn.byteforce.seedj.starter.eis.domain.internal.EventTypeRegistry;
import cn.byteforce.seedj.starter.eis.domain.spi.EventPayloadCodec;
import cn.byteforce.seedj.starter.eis.domain.spi.EventUpcasterRegistry;
import cn.byteforce.seedj.starter.eis.domain.spi.TenantResolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动配置：核心 Bean 装配、无 JdbcTemplate 时不装配事件日志、kill-switch。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisDomainAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EisDomainAutoConfiguration.class));

    @Test
    @DisplayName("默认装配注册表、编解码器与租户解析；无 JdbcTemplate 时不装配事件日志")
    void assemblesCoreBeansWithoutJdbc() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EventTypeRegistry.class);
            assertThat(context).hasSingleBean(EventUpcasterRegistry.class);
            assertThat(context).hasSingleBean(EventPayloadCodec.class);
            assertThat(context).hasSingleBean(TenantResolver.class);
            assertThat(context).doesNotHaveBean(EventLogStore.class);
        });
    }

    @Test
    @DisplayName("存在 JdbcTemplate 时装配 PostgreSQL 事件日志")
    void assemblesEventLogWithJdbc() {
        runner.withBean(JdbcTemplate.class, () -> new JdbcTemplate(new DriverManagerDataSource()))
                .run(context -> {
            assertThat(context).hasSingleBean(EventLogStore.class);
            assertThat(context.getBean(EisDomainProperties.class).isTenantRequired()).isTrue();
        });
    }

    @Test
    @DisplayName("kill-switch：byteforce.eis.domain.enabled=false 时不装配")
    void disabledByProperty() {
        runner.withPropertyValues("byteforce.eis.domain.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(EventTypeRegistry.class));
    }

    @Test
    @DisplayName("应用可覆盖编解码器")
    void appBeanOverridesCodec() {
        runner.withBean(EventPayloadCodec.class, () -> new NoopCodec()).run(context ->
                assertThat(context.getBean(EventPayloadCodec.class)).isInstanceOf(NoopCodec.class));
    }

    /**
     * 应用自定义编解码器占位。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    static final class NoopCodec implements EventPayloadCodec {

        @Override
        public String encode(DomainEvent event) {
            return "{}";
        }

        @Override
        public DomainEvent decode(String eventType, int schemaVersion, String payload) {
            throw new UnsupportedOperationException("noop");
        }
    }
}
