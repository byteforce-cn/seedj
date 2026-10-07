package cn.byteforce.seedj.starter.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 授权服务端到端冒烟测试：Boot 原生授权服务器自动配置与 seedj 缺省共同生效，
 * client_credentials 令牌可签发（客户端来自 Boot 原生属性）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@SpringBootTest(classes = AuthorizationTestApplication.class, properties = {
        "byteforce.security.authorization.issuer=https://auth.example.com",
        "spring.security.oauth2.authorizationserver.client.test-client.registration.client-id=test-client",
        "spring.security.oauth2.authorizationserver.client.test-client.registration.client-secret={noop}secret",
        "spring.security.oauth2.authorizationserver.client.test-client.registration.client-authentication-methods=client_secret_basic",
        "spring.security.oauth2.authorizationserver.client.test-client.registration.authorization-grant-types=client_credentials",
        "spring.security.oauth2.authorizationserver.client.test-client.registration.scopes=read"
})
@AutoConfigureMockMvc
class AuthorizationServerSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void clientCredentialsTokenIsIssued() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basic("test-client", "secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("scope", "read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.token_type").value("Bearer"));
    }

    @Test
    void wrongSecretIsRejected() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basic("test-client", "wrong"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    private static String basic(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
