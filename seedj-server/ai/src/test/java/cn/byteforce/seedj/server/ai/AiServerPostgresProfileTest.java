package cn.byteforce.seedj.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.infra.jdbc.ai.PostgresDecisionLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code postgres} profile：宿主在「有数据源」的真实形态下能起来，且端点匿名可达。
 *
 * <p>这条用例守的是两类会静默腐烂的配置：
 *
 * <ul>
 *   <li><b>只有有数据源时才会暴露的键</b>——{@code seedj.object.provider=memory}（决策面宿主没有业务对象，默认
 *       {@code postgres} 会因无 schema 声明直接启动失败）等，默认 profile 的用例排除了数据源自动配置，从来看不到它们；
 *   <li><b>鉴权口径</b>——JWKS 必须匿名可读（执行侧按 {@code kid} 拉公钥），默认公开路径里没有它，靠
 *       {@code seedj.biz.public-paths} 显式加；这里走真实安全链（不是 {@code dev-auth} 放行链），不带任何
 *       {@code Authorization} 也能取到才算数。
 * </ul>
 *
 * <p>启动本身就会跑 {@code InfrastructureInitializer} 的迁移，因此「上下文起来了」同时意味着账本/命令/审计的表建好了；
 * 用例再钉一条 {@code agent_decision_record} 可查，避免迁移被跳过还绿。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles({"postgres", "test"})
@Import(AiServerPostgresProfileTest.StubJwtConfiguration.class)
class AiServerPostgresProfileTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * 挡掉启动期的 OIDC 元数据拉取：本用例只关心「匿名请求能不能进公开路径」，不验证令牌解析。
     *
     * <p>真实安全链仍在（{@code seedj.biz.dev-auth.enabled} 保持 false），所以公开路径之外的请求照样 401。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubJwtConfiguration {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new JwtException("postgres profile test does not accept JWTs");
            };
        }
    }

    @Autowired private MockMvc mvc;

    @Autowired private ApplicationContext context;

    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("postgres profile 起得来：账本走 PostgreSQL，迁移已执行")
    void postgres_profile_selects_postgres_ledger() {
        assertThat(context.getBean(DecisionLedger.class)).isInstanceOf(PostgresDecisionLedger.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_decision_record", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("健康检查匿名可达")
    void health_is_public() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("JWKS 匿名可达：公开路径里必须有它，否则执行侧拿不到公钥")
    void jwks_is_public() throws Exception {
        mvc.perform(get("/.well-known/agent-decision-jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("OKP"));
    }
}
