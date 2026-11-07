package cn.byteforce.seedj.starter.iam.nativeauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** native-auth 失败响应：MFA 挑战体与非 MFA 错误委派。 */
class NativeAuthErrorResponseHandlerTest {

    private final NativeAuthErrorResponseHandler handler = new NativeAuthErrorResponseHandler();

    @Test
    @DisplayName("MFA 挑战写出 error=mfa_required 与挑战参数")
    void mfaRequiredWritesChallengeBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new NativeAuthMfaRequiredException("mfa-token-1", List.of("totp", "recovery")));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getContentAsString())
                .contains("\"error\":\"mfa_required\"")
                .contains("\"mfa_token\":\"mfa-token-1\"")
                .contains("recovery");
    }

    @Test
    @DisplayName("非 MFA 失败委派给 OAuth2 标准错误响应")
    void otherFailuresDelegateToOAuth2Handler() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_grant",
                        "invalid credentials", null)));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("invalid_grant");
    }
}
