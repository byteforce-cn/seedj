package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link EisAuthzClientCredentialsTokenProvider} 令牌获取/缓存/刷新测试
 * （client_credentials + Bearer 注入，MockRestServiceServer）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuthzClientCredentialsTokenProviderTest {

    private static final String TOKEN_URI = "http://iam.test/oauth2/token";

    private static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString("svc-hr:s3cr3t".getBytes(StandardCharsets.UTF_8));

    @Test
    void fetchesTokenWithClientCredentialsAndCachesUntilExpiry() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://authz.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-15T12:00:00Z"));
        EisAuthzClientCredentialsTokenProvider provider = new EisAuthzClientCredentialsTokenProvider(
                builder.build(), TOKEN_URI, "svc-hr", "s3cr3t", "authz.read", Duration.ofSeconds(10), clock);
        EisAuthzClient client = new EisAuthzClient(builder.build(), provider);

        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("grant_type=client_credentials")))
                .andExpect(content().string(containsString("scope=authz.read")))
                .andRespond(withSuccess("{\"access_token\":\"t1\",\"token_type\":\"Bearer\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://authz.test/check"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t1"))
                .andRespond(allowedResponse());
        server.expect(requestTo("http://authz.test/check"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t1"))
                .andRespond(allowedResponse());

        assertThat(client.check("p", "x", clock.instant(), null).isAllowed()).isTrue();
        clock.advance(Duration.ofSeconds(200));
        assertThat(client.check("p", "x", clock.instant(), null).isAllowed()).isTrue();

        server.verify();
    }

    @Test
    void refreshesTokenAfterExpiry() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://authz.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-15T12:00:00Z"));
        EisAuthzClientCredentialsTokenProvider provider = new EisAuthzClientCredentialsTokenProvider(
                builder.build(), TOKEN_URI, "svc-hr", "s3cr3t", null, Duration.ofSeconds(10), clock);
        EisAuthzClient client = new EisAuthzClient(builder.build(), provider);

        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"t1\",\"expires_in\":100}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://authz.test/check"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t1"))
                .andRespond(allowedResponse());
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"t2\",\"expires_in\":100}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://authz.test/check"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t2"))
                .andRespond(allowedResponse());

        assertThat(client.check("p", "x", clock.instant(), null).isAllowed()).isTrue();
        clock.advance(Duration.ofSeconds(91));
        assertThat(client.check("p", "x", clock.instant(), null).isAllowed()).isTrue();

        server.verify();
    }

    @Test
    void incompleteCredentialsFailFast() {
        RestClient tokenClient = RestClient.builder().build();
        assertThatThrownBy(() -> new EisAuthzClientCredentialsTokenProvider(tokenClient, " ", "id", "secret",
                null, Duration.ofSeconds(5), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token-uri");
        assertThatThrownBy(() -> new EisAuthzClientCredentialsTokenProvider(tokenClient, TOKEN_URI, "id", " ",
                null, Duration.ofSeconds(5), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-secret");
    }

    @Test
    void tokenEndpointErrorRaisesRemoteException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EisAuthzClientCredentialsTokenProvider provider = new EisAuthzClientCredentialsTokenProvider(
                builder.build(), TOKEN_URI, "svc-hr", "wrong", null, Duration.ofSeconds(5), Clock.systemUTC());
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));

        assertThatThrownBy(provider::accessToken)
                .isInstanceOf(EisAuthzRemoteException.class)
                .satisfies(ex -> {
                    assertThat(((EisAuthzRemoteException) ex).getStatus()).isEqualTo(401);
                    assertThat(((EisAuthzRemoteException) ex).getPath()).isEqualTo(TOKEN_URI);
                });
        server.verify();
    }

    private static org.springframework.test.web.client.ResponseCreator allowedResponse() {
        return withSuccess("{\"allowed\":true,\"reason\":\"允许\",\"reason_code\":\"ALLOWED\"}",
                MediaType.APPLICATION_JSON);
    }

    /**
     * 可推进时钟（令牌过期刷新用例）。
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
