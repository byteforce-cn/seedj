package cn.byteforce.seedj.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 宿主「不写就起不来」的签发方配置：缺 token issuer 时启动期就失败。
 *
 * <p>决策面的调用方凭 IAM 签发的访问令牌来访，所以 biz 资源服务器必须知道 issuer： {@code
 * spring.security.oauth2.resourceserver.jwt.issuer-uri} 是 base {@code application.yml} 里那半个
 * 「不写就起不来」的键，另一半是 {@code seedj.object.provider=memory}（由 {@link AiServerPostgresProfileTest}
 * 在**有数据源**的形态下覆盖——它删掉这个键就起不来）。这里补的是 issuer 这一半： {@link AiServerPostgresProfileTest} 用 stub {@code
 * JwtDecoder} 挡掉了启动期的 OIDC 拉取， 恰好绕开了唯一读 issuer 的那行代码，删掉 yml 里的 issuer 它照样绿。
 *
 * <p>两条用例合起来才有牙：{@link #host_supplies_token_issuer()} 断言 base {@code application.yml} 确实提供了
 * issuer（删掉那行就红），{@link #host_requires_token_issuer()} 断言缺了它宿主启动失败并点名配置项。
 *
 * <p>不联网也不依赖 IAM：把 issuer 覆盖成空值后，{@code BizSecurityAutoConfiguration.resolveIssuer} 在拉元数据
 * <b>之前</b>就抛错，所以本用例是确定性的。
 */
class AiServerIssuerConfigurationTest {

    /** {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}：唯一读 issuer 的键。 */
    private static final String ISSUER_URI = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    @Test
    @DisplayName("宿主自带 issuer：base application.yml 的 SEEDJ_IAM_ISSUER 占位置到 http://localhost:9000")
    void host_supplies_token_issuer() {
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(AiServer.class)
                        .web(WebApplicationType.NONE)
                        .profiles("test")
                        .properties(
                                AiServerTestFixtures.NO_DATASOURCE,
                                AiServerTestFixtures.AUDIT_OFF,
                                AiServerTestFixtures.DEGRADED_MODE)
                        .run()) {
            assertThat(context.getEnvironment().getProperty(ISSUER_URI))
                    .as("base application.yml 必须给出 issuer 占位（与 seedj-server/im 同一个环境变量与默认值）")
                    .isEqualTo("http://localhost:9000");
        }
    }

    @Test
    @DisplayName("缺 token issuer 时宿主启动失败并点名配置项")
    void host_requires_token_issuer() {
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
                                                AiServerTestFixtures.DEGRADED_MODE)
                                        .run(
                                                "--server.port=0",
                                                // 命令行参数优先于 application.yml：只有它压得住 base yml 里的
                                                // issuer 占位（builder.properties() 是最低优先级的默认值）。
                                                "--spring.security.oauth2.resourceserver.jwt"
                                                        + ".issuer-uri=",
                                                "--seedj.biz.dev-auth.enabled=false"))
                .hasMessageContaining("requires a token issuer");
    }
}
