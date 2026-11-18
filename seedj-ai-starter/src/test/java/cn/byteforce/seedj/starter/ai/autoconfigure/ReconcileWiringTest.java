package cn.byteforce.seedj.starter.ai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.plane.DecisionController;
import cn.byteforce.seedj.starter.ai.plane.OutcomeDeriver;
import cn.byteforce.seedj.starter.ai.plane.PlaneReconciliationSource;
import cn.byteforce.seedj.starter.ai.plane.ReconciliationJob;
import cn.byteforce.seedj.starter.web.ContractErrorHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 上报与对账的装配边界（spec §6.4 两段式与对账；R4 数据源、R5 配置项）。
 *
 * <p>三件事只在装配上成立，用单元测试跑不出来：<b>没有对账数据源就不装配</b>上报与对账（决策面本身照常可用、
 * 上报端点 503）、<b>数据源可以按 bean 名指定</b>、<b>定时对账是显式开启的</b>（默认 false，且宿主还要自己写
 * {@code @EnableScheduling}）。这里用 {@link WebApplicationContextRunner} 只看 bean 面，不连库：数据源只被当作
 * bean 解析，构造期不建连接。
 */
class ReconcileWiringTest {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("018f2e3a-0000-7000-8000-000000000118");

    private static final WebApplicationContextRunner RUNNER =
            new WebApplicationContextRunner()
                    .withUserConfiguration(PlaneHost.class)
                    .withConfiguration(AutoConfigurations.of(DecisionPlaneAutoConfiguration.class))
                    .withPropertyValues(
                            "seedj.ai.plane.enabled=true",
                            "seedj.ai.plane.tenant=" + TENANT,
                            "seedj.ai.identity.actor=" + ACTOR,
                            "seedj.ai.identity.on-behalf-of=" + ON_BEHALF_OF);

    @Test
    @DisplayName("有主数据源：装配上报与对账，定时包装默认关闭")
    void reconcile_beans_need_a_datasource() {
        RUNNER.withUserConfiguration(BusinessDataSourceHost.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(OutcomeDeriver.class);
                            assertThat(context).hasSingleBean(ReconciliationJob.class);
                            assertThat(context).hasSingleBean(PlaneReconciliationSource.class);
                            assertThat(context).doesNotHaveBean(ReconciliationJob.Scheduler.class);
                            assertThat(context).hasSingleBean(DecisionController.class);
                        });
    }

    @Test
    @DisplayName("没有数据源：不装配上报与对账，决策面照常可用，上报端点 503")
    void missing_datasource_skips_outcome_reporting() {
        RUNNER.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OutcomeDeriver.class);
                    assertThat(context).doesNotHaveBean(ReconciliationJob.class);
                    assertThat(context).hasSingleBean(DecisionController.class);
                    MockMvc mvc =
                            MockMvcBuilders.standaloneSetup(
                                            context.getBean(DecisionController.class))
                                    .setControllerAdvice(new ContractErrorHandler())
                                    .build();
                    mvc.perform(
                                    post("/v1/decisions/" + UUID.randomUUID() + "/outcome")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"command_id\": \""
                                                            + UUID.randomUUID()
                                                            + "\"}"))
                            .andExpect(status().isServiceUnavailable());
                });
    }

    @Test
    @DisplayName("seedj.ai.plane.reconcile.enabled=true 才装配定时包装")
    void scheduled_reconcile_is_opt_in() {
        RUNNER.withUserConfiguration(BusinessDataSourceHost.class)
                .withPropertyValues("seedj.ai.plane.reconcile.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(ReconciliationJob.Scheduler.class);
                        });
    }

    @Test
    @DisplayName("数据源按 bean 名指定：名字对得上才装配，对不上启动失败并点名")
    void named_reconcile_datasource_is_resolved_by_bean_name() {
        RUNNER.withUserConfiguration(BusinessDataSourceHost.class)
                .withPropertyValues("seedj.ai.plane.reconcile.datasource=business")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(OutcomeDeriver.class);
                        });

        RUNNER.withUserConfiguration(BusinessDataSourceHost.class)
                .withPropertyValues("seedj.ai.plane.reconcile.datasource=missing")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(messages(context.getStartupFailure())).contains("missing");
                        });
    }

    private static String messages(Throwable error) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = error; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return String.join(" | ", messages);
    }

    /** 装配期宿主：只给启用标记、时钟与账本，其余 bean（含数据源）由各用例自己加。 */
    @Configuration(proxyBeanMethods = false)
    static class PlaneHost {

        @Bean
        AiEnabledMarker aiEnabledMarker() {
            return new AiEnabledMarker();
        }

        @Bean
        Clock clock() {
            return () -> Instant.parse("2026-11-17T09:41:00Z");
        }

        @Bean
        DecisionLedger decisionLedger() {
            return new InMemoryDecisionLedger();
        }
    }

    /** 业务库宿主：一个具名对账数据源（只用于 bean 解析，不建连接）。 */
    @Configuration(proxyBeanMethods = false)
    static class BusinessDataSourceHost {

        @Bean(name = "business")
        DataSource businessDataSource() {
            return new DriverManagerDataSource(
                    "jdbc:postgresql://localhost:5432/business", "seedj", "seedj");
        }
    }
}
