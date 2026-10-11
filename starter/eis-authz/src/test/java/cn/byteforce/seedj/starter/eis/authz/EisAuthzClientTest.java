package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link EisAuthzClient} 请求/响应映射测试（MockRestServiceServer）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuthzClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private MockRestServiceServer server;

    private EisAuthzClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://authz.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EisAuthzClient(builder.build());
    }

    @Test
    void checkParsesResponse() {
        server.expect(requestTo("http://authz.test/check"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.principal").value("away"))
                .andExpect(jsonPath("$.permission").value("purchase.request.approve"))
                .andExpect(jsonPath("$.as_of").value("2026-10-15T12:00:00Z"))
                .andRespond(withSuccess("{\"allowed\":false,\"reason\":\"违反职责分离 SOD-001\","
                        + "\"reason_code\":\"SOD-001\",\"warnings\":[\"SOD-003\"]}", MediaType.APPLICATION_JSON));

        EisAuthzDecision decision = client.check("away", "purchase.request.approve",
                Instant.parse("2026-10-15T12:00:00Z"), null);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("SOD-001");
        assertThat(decision.getWarnings()).containsExactly("SOD-003");
        server.verify();
    }

    @Test
    void resolveSendsScopeAndRecordedTime() {
        UUID principal = UUID.randomUUID();
        server.expect(requestTo("http://authz.test/resolve"))
                .andExpect(jsonPath("$.recorded_as_of").value("2026-10-14T00:00:00Z"))
                .andExpect(jsonPath("$.scope.department").value("D001"))
                .andRespond(withSuccess("{\"principal\":\"" + principal + "\","
                        + "\"as_of\":\"2026-10-15T12:00:00Z\",\"roles\":[\"approver\"],"
                        + "\"permissions\":[\"purchase.request.approve\"],\"sod_warnings\":[]}",
                        MediaType.APPLICATION_JSON));

        EisAuthzResolution resolution = client.resolve("away", Instant.parse("2026-10-15T12:00:00Z"),
                Instant.parse("2026-10-14T00:00:00Z"), MAPPER.readTree("{\"department\":\"D001\"}"));

        assertThat(resolution.getPrincipal()).isEqualTo(principal);
        assertThat(resolution.getRoles()).containsExactly("approver");
        assertThat(resolution.getAsOf()).isEqualTo(Instant.parse("2026-10-15T12:00:00Z"));
        server.verify();
    }

    @Test
    void errorStatusRaisesRemoteException() {
        server.expect(requestTo("http://authz.test/check"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"EIS-404-001\",\"message\":\"主体不存在\"}"));

        assertThatThrownBy(() -> client.check("nobody", "x", Instant.parse("2026-10-15T12:00:00Z"), null))
                .isInstanceOf(EisAuthzRemoteException.class)
                .satisfies(ex -> {
                    assertThat(((EisAuthzRemoteException) ex).getStatus()).isEqualTo(404);
                    assertThat(((EisAuthzRemoteException) ex).getPath()).isEqualTo("/check");
                });
        server.verify();
    }
}
