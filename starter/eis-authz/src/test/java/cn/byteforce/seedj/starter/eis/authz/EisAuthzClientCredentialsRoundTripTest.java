package cn.byteforce.seedj.starter.eis.authz;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import cn.byteforce.seedj.starter.eis.core.EisCoreAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费方服务间认证真实 HTTP 往返测试：client_credentials 取令牌 → Bearer 调用
 * {@code /check} → 短 TTL 过期后自动刷新（旧令牌被服务端拒绝，刷新失败即测试失败）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@SpringBootTest(classes = EisAuthzClientCredentialsRoundTripTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EisAuthzClientCredentialsRoundTripTest {

    @Autowired
    private StubAuthorizationServer stubServer;

    @LocalServerPort
    private int port;

    @Test
    void clientCredentialsRoundTripRefreshesExpiredToken() throws InterruptedException {
        EisAuthzClient client = new EisAuthzClient(
                RestClient.builder().baseUrl("http://localhost:" + port).build(),
                new EisAuthzClientCredentialsTokenProvider(RestClient.builder().build(),
                        "http://localhost:" + port + "/oauth2/token", "svc-hr", "s3cr3t", "authz.read",
                        Duration.ZERO));

        assertThat(client.check("p", "iam.user.create", Instant.now(), null).isAllowed()).isTrue();
        assertThat(stubServer.issuedTokens()).isEqualTo(1);
        assertThat(stubServer.lastScope()).isEqualTo("authz.read");

        Thread.sleep(1200);
        assertThat(client.check("p", "iam.user.create", Instant.now(), null).isAllowed()).isTrue();
        assertThat(stubServer.issuedTokens()).isEqualTo(2);
    }

    /**
     * 测试应用（嵌入式 HTTP 服务）。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {EisAuthzAutoConfiguration.class, EisCoreAutoConfiguration.class})
    static class TestApplication {

        /**
         * 桩授权服务（令牌端点 + /check）。
         *
         * @return 桩服务
         */
        @Bean
        StubAuthorizationServer stubAuthorizationServer() {
            return new StubAuthorizationServer();
        }
    }

    /**
     * 桩授权服务：令牌 1 秒过期；仅接受「当前」令牌（旧令牌 401）——
     * 客户端未刷新即被拒，刷新语义由真实 HTTP 往返证明。
     */
    @RestController
    static class StubAuthorizationServer {

        private final AtomicInteger issuedTokens = new AtomicInteger();

        private volatile String currentToken;

        private volatile String lastScope;

        @PostMapping("/oauth2/token")
        Map<String, Object> token(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                  @RequestParam Map<String, String> form) {
            if (auth == null || !auth.startsWith("Basic ")) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少客户端认证");
            }
            if (!"client_credentials".equals(form.get("grant_type"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "grant_type 非法");
            }
            lastScope = form.get("scope");
            currentToken = "tok-" + issuedTokens.incrementAndGet();
            return Map.of("access_token", currentToken, "token_type", "Bearer", "expires_in", 1);
        }

        @PostMapping("/check")
        Map<String, Object> check(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
            if (!("Bearer " + currentToken).equals(auth)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证或登录已过期");
            }
            return Map.of("allowed", true, "reason", "允许", "reason_code", "ALLOWED");
        }

        int issuedTokens() {
            return issuedTokens.get();
        }

        String lastScope() {
            return lastScope;
        }
    }
}
