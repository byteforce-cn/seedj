package cn.byteforce.seedj.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.starter.ai.guard.AgentGuardFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 决策面宿主的上下文契约：端点可达、只当决策面、启动期配置校验。
 *
 * <p>宿主默认角色是<b>决策面</b>（{@code seedj.ai.plane.enabled=true}）：暴露 {@code /v1/decisions} 与 JWKS；
 * {@code seedj.ai.guard.enabled=false} 则让它不同时充当执行侧守卫——两侧都开会让同一个进程既签发 token 又校验
 * token，出问题时无法判断是哪一侧的配置生效。
 *
 * <p>用例不依赖真实 PostgreSQL：{@code DataSourceAutoConfiguration} 被排除（其余 JDBC 自动配置随之退出），账本由
 * {@link InMemoryDecisionLedger} 提供（与 PG 账本同语义）。调用方由 {@code seedj.biz.dev-auth} 注入，所以这里验证的
 * 是「宿主把 starter 装配起来了」，不是鉴权矩阵。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            AiServerContextTest.NO_DATASOURCE,
            AiServerContextTest.AUDIT_OFF,
            AiServerContextTest.DEGRADED_MODE,
            AiServerContextTest.DEV_AUTH,
            AiServerContextTest.DERIVED_FIELD_KEYS
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AiServerContextTest.InMemoryLedgerConfiguration.class)
class AiServerContextTest {

    /**
     * 无 PostgreSQL 的本地覆盖：数据源自动配置退出（JdbcTemplate 与事务管理器随之不装配）、审计关闭、命令运行时降级、
     * 本地固定调用方。用例 4 用同一份覆盖启动真宿主，因此它们只在这里写一次。
     */
    static final String NO_DATASOURCE =
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";

    static final String AUDIT_OFF = "seedj.audit.enabled=false";

    static final String DEGRADED_MODE = "seedj.command.degraded-mode=true";

    static final String DEV_AUTH = "seedj.biz.dev-auth.enabled=true";

    static final String DERIVED_FIELD_KEYS = "seedj.security.derived-field-keys=context-test";

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryLedgerConfiguration {

        @Bean
        DecisionLedger decisionLedger() {
            return new InMemoryDecisionLedger();
        }
    }

    @Autowired private MockMvc mvc;

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("决策面端点已装配：JWKS 200 且发布 OKP 公钥，空决策请求 400")
    void plane_endpoints_are_exposed() throws Exception {
        mvc.perform(get("/.well-known/agent-decision-jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("OKP"));

        mvc.perform(post("/v1/decisions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("决策面宿主不装配 guard：不与执行侧角色混用")
    void guard_is_off_in_plane_host() {
        assertThat(context.getBeanNamesForType(AgentGuardFilter.class)).isEmpty();
    }

    @Test
    @DisplayName("actuator 健康检查可用")
    void actuator_health_is_up() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("宿主配置缺 seedj.ai.plane.tenant 时启动失败并点名配置键")
    void plane_requires_tenant() {
        assertThatThrownBy(
                        () ->
                                new SpringApplicationBuilder(
                                                AiServer.class, InMemoryLedgerConfiguration.class)
                                        .profiles("test")
                                        .properties(
                                                NO_DATASOURCE,
                                                AUDIT_OFF,
                                                DEGRADED_MODE,
                                                DEV_AUTH,
                                                DERIVED_FIELD_KEYS)
                                        .run("--server.port=0", "--seedj.ai.plane.tenant="))
                .hasMessageContaining("seedj.ai.plane.tenant");
    }
}
