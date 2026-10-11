package cn.byteforce.seedj.starter.eis.audit;

import cn.byteforce.seedj.starter.eis.audit.web.EisAuditAdminController;
import cn.byteforce.seedj.starter.eis.core.EisClock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link EisAuditAutoConfiguration} 装配边界测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuditAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EisAuditAutoConfiguration.class))
            .withBean("jdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean("eisClock", EisClock.class, () -> EisClock.fixed(Instant.parse("2026-10-10T09:00:00Z")));

    @Test
    void registersChainAndDefaultLoggingAnchor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EisAuditChain.class);
            assertThat(context).hasSingleBean(EisAuditAnchor.class);
            assertThat(context.getBean(EisAuditAnchor.class)).isInstanceOf(LoggingEisAuditAnchor.class);
        });
    }

    @Test
    void anchorFilePropertySwitchesToFileAnchor() {
        runner.withPropertyValues("byteforce.eis.audit.anchor-file=/tmp/eis-audit-test/anchor.log")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EisAuditAnchor.class)).isInstanceOf(FileEisAuditAnchor.class);
                });
    }

    @Test
    void disabledByPropertyRegistersNothing() {
        runner.withPropertyValues("byteforce.eis.audit.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EisAuditChain.class);
                    assertThat(context).doesNotHaveBean(EisAuditAnchor.class);
                });
    }

    @Test
    void webRunnerRegistersAdminController() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EisAuditAutoConfiguration.class))
                .withBean("jdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean("eisClock", EisClock.class, () -> EisClock.fixed(Instant.parse("2026-10-10T09:00:00Z")))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EisAuditAdminController.class);
                });
    }
}
