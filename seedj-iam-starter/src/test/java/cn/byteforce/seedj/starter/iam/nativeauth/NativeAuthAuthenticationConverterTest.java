package cn.byteforce.seedj.starter.iam.nativeauth;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.iam.security.NativeAuthGrantType;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/** native-auth 授权请求解析：只认 POST + 自定义 grant。 */
class NativeAuthAuthenticationConverterTest {

    private final NativeAuthAuthenticationConverter converter =
            new NativeAuthAuthenticationConverter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("非 POST 请求返回 null")
    void nonPostRequestIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/token");
        request.setParameter("grant_type", NativeAuthGrantType.NATIVE_AUTH.getValue());

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("其它 grant_type 返回 null")
    void otherGrantTypeIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setParameter("grant_type", "password");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("未认证客户端时 registeredClient 为 null")
    void missingClientAuthenticationYieldsNullClient() {
        MockHttpServletRequest request = nativeRequest();
        request.setParameter("username", "alice");

        NativeAuthAuthenticationToken token =
                (NativeAuthAuthenticationToken) converter.convert(request);

        assertThat(token.registeredClient()).isNull();
        assertThat(token.clientPrincipal()).isNull();
        assertThat(token.username()).isEqualTo("alice");
    }

    @Test
    @DisplayName("解析全部表单参数并把 scope 拆成去重集合")
    void parsesFormParametersAndScopes() {
        RegisteredClient client =
                RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId("native-app")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                        .authorizationGrantType(NativeAuthGrantType.NATIVE_AUTH)
                        .build();
        OAuth2ClientAuthenticationToken principal =
                new OAuth2ClientAuthenticationToken(
                        client, ClientAuthenticationMethod.NONE, "native-app");
        SecurityContextHolder.getContext().setAuthentication(principal);
        MockHttpServletRequest request = nativeRequest();
        request.setParameter("username", "alice");
        request.setParameter("password", "alice-password");
        request.setParameter("mfa_token", "mfa-1");
        request.setParameter("otp", "123456");
        request.setParameter("device_id", "device-1");
        request.setParameter("device_name", "Pixel");
        request.setParameter("app_version", "1.0.0");
        request.addParameter("scope", "openid profile");
        request.addParameter("scope", "  profile  offline_access ", " ");

        NativeAuthAuthenticationToken token =
                (NativeAuthAuthenticationToken) converter.convert(request);

        assertThat(token.registeredClient()).isSameAs(client);
        assertThat(token.clientPrincipal()).isSameAs(principal);
        assertThat(token.username()).isEqualTo("alice");
        assertThat(token.password()).isEqualTo("alice-password");
        assertThat(token.mfaToken()).isEqualTo("mfa-1");
        assertThat(token.otp()).isEqualTo("123456");
        assertThat(token.deviceId()).isEqualTo("device-1");
        assertThat(token.deviceName()).isEqualTo("Pixel");
        assertThat(token.appVersion()).isEqualTo("1.0.0");
        assertThat(token.scopes()).isEqualTo(Set.of("openid", "profile", "offline_access"));
        assertThat(token.isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("无 scope 参数时得到空集合")
    void missingScopesYieldEmptySet() {
        MockHttpServletRequest request = nativeRequest();

        Authentication authentication = converter.convert(request);

        assertThat(((NativeAuthAuthenticationToken) authentication).scopes()).isEmpty();
    }

    private static MockHttpServletRequest nativeRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setParameter("grant_type", NativeAuthGrantType.NATIVE_AUTH.getValue());
        return request;
    }
}
