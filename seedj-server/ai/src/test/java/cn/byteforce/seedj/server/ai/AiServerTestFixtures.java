package cn.byteforce.seedj.server.ai;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 宿主「无数据源」启动用例的公共夹具：三条覆盖项 + 内存账本。
 *
 * <p>两个启动失败用例（缺 {@code seedj.ai.plane.tenant}、缺 token issuer）必须先让宿主的其它依赖都满足，失败原因才
 * 恰好只剩被覆盖的那一个键：数据源自动配置退出、审计关闭、命令运行时降级、账本用内存实现（starter 故意不提供内存账本
 * bean）。这些覆盖项只在这里写一次——放进各自的用例里就会漂移，而漂移会把失败原因变成「别的键没配」。
 */
final class AiServerTestFixtures {

    /** 无 PostgreSQL：数据源自动配置退出，JdbcTemplate 与事务管理器随之不装配。 */
    static final String NO_DATASOURCE =
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";

    /** 审计默认开启，要求审计存储 + 事务管理器；无数据源时两者都没有。 */
    static final String AUDIT_OFF = "seedj.audit.enabled=false";

    /** 命令运行时缺事务/锁时只允许在 local/test 降级运行（{@code DegradedModeGuard} 按 profile 放行）。 */
    static final String DEGRADED_MODE = "seedj.command.degraded-mode=true";

    /** 本地固定调用方，避免上下文测试拉起 IdP。 */
    static final String DEV_AUTH = "seedj.biz.dev-auth.enabled=true";

    /** 决策面要求 {@code agent:decide}：dev 调用方必须带它，否则请求停在 403、进不到控制器。 */
    static final String DEV_AUTH_DECIDE = "seedj.biz.dev-auth.permissions=agent:decide";

    private AiServerTestFixtures() {}

    /** 测试账本：与 PG 账本同语义的内存实现。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryLedgerConfiguration {

        @Bean
        DecisionLedger decisionLedger() {
            return new InMemoryDecisionLedger();
        }
    }
}
