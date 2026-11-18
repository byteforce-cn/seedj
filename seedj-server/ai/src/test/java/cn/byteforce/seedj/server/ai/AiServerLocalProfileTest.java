package cn.byteforce.seedj.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code local} profile：零外部依赖可启动（无数据库、无 IdP、无 IAM）。
 *
 * <p>{@code local} 的语义是「内存一切、重启即丢」（见 {@code docs/guide/configuration.md}），所以这里断言两件事：数据源 自动配置确实退出（没有
 * {@link DataSource} bean），账本退化为 {@link InMemoryDecisionLedger}。真数据库形态由 {@link
 * AiServerPostgresProfileTest} 覆盖。
 */
@SpringBootTest
@ActiveProfiles("local")
class AiServerLocalProfileTest {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("local profile 无数据源也能启动，账本是内存实现")
    void local_profile_starts_without_datasource() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBean(DecisionLedger.class))
                .isInstanceOf(InMemoryDecisionLedger.class);
    }
}
