package cn.byteforce.seedj.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.starter.ai.guard.AgentGuardFilter;
import cn.byteforce.seedj.starter.security.BizSecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 决策面宿主的上下文契约：端点可达、只当决策面、启动期配置校验。
 *
 * <p>宿主默认角色是<b>决策面</b>（{@code seedj.ai.plane.enabled=true}）：暴露 {@code /v1/decisions} 与 JWKS； {@code
 * seedj.ai.guard.enabled=false} 则让它不同时充当执行侧守卫——两侧都开会让同一个进程既签发 token 又校验 token，出问题时无法判断是哪一侧的配置生效。
 *
 * <p>用例不依赖真实 PostgreSQL：{@code DataSourceAutoConfiguration} 被排除（其余 JDBC 自动配置随之退出），账本由 {@link
 * AiServerTestFixtures} 里的内存实现提供（与 PG 账本同语义）。调用方由 {@code seedj.biz.dev-auth} 注入，所以这里验证的 是「宿主把
 * starter 装配起来了」，不是鉴权矩阵——真实安全链下的匿名可达性在 {@link AiServerPostgresProfileTest} 里断言； 这里只钉住「JWKS
 * 路径确实在公开路径清单里」，防止配置被删掉还绿。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            AiServerTestFixtures.NO_DATASOURCE,
            AiServerTestFixtures.AUDIT_OFF,
            AiServerTestFixtures.DEGRADED_MODE,
            AiServerTestFixtures.DEV_AUTH,
            AiServerTestFixtures.DEV_AUTH_DECIDE
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AiServerTestFixtures.InMemoryLedgerConfiguration.class)
class AiServerContextTest {

    @Autowired private MockMvc mvc;

    @Autowired private ApplicationContext context;

    @Autowired private BizSecurityProperties security;

    @Test
    @DisplayName("决策面端点已装配：JWKS 不带 Authorization 也 200 且发布 OKP 公钥，空决策请求 400")
    void plane_endpoints_are_exposed() throws Exception {
        mvc.perform(get("/.well-known/agent-decision-jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("OKP"));
        assertThat(security.publicPaths())
                .as("JWKS 必须列进 seedj.biz.public-paths，否则执行侧拿不到公钥")
                .contains("/.well-known/agent-decision-jwks.json");

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
                                                AiServer.class,
                                                AiServerTestFixtures.InMemoryLedgerConfiguration
                                                        .class)
                                        .profiles("test")
                                        .properties(
                                                AiServerTestFixtures.NO_DATASOURCE,
                                                AiServerTestFixtures.AUDIT_OFF,
                                                AiServerTestFixtures.DEGRADED_MODE,
                                                AiServerTestFixtures.DEV_AUTH,
                                                AiServerTestFixtures.DEV_AUTH_DECIDE)
                                        .run("--server.port=0", "--seedj.ai.plane.tenant="))
                .hasMessageContaining("seedj.ai.plane.tenant");
    }
}
