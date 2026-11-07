package cn.byteforce.seedj.starter.iam.web;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** IAM 管理端点的错误码到 HTTP 状态映射。 */
class IamExceptionHandlerTest {

    private final IamExceptionHandler handler = new IamExceptionHandler();

    @ParameterizedTest
    @CsvSource({
            "INVALID_CREDENTIALS, 401",
            "ACCOUNT_DISABLED, 401",
            "ACCOUNT_LOCKED, 401",
            "TOTP_INVALID, 401",
            "TOTP_REPLAY, 401",
            "RECOVERY_CODE_INVALID, 401",
            "MFA_CHALLENGE_INVALID, 401",
            "MFA_CHALLENGE_EXPIRED, 401",
            "MFA_CHALLENGE_LOCKED, 401",
            "SCIM_UNAUTHORIZED, 401",
            "FORBIDDEN, 403",
            "USER_NOT_FOUND, 404",
            "GROUP_NOT_FOUND, 404",
            "ROLE_NOT_FOUND, 404",
            "POLICY_NOT_FOUND, 404",
            "NOT_FOUND, 404",
            "PERMISSION_NOT_FOUND, 404",
            "RATE_LIMITED, 429",
            "NATIVE_AUTH_INVALID_CLIENT, 401",
            "USER_CONFLICT, 409",
            "GROUP_CONFLICT, 409",
            "ROLE_CONFLICT, 409",
            "CONFLICT, 409",
            "SCIM_CONFLICT, 409",
            "SCIM_PRECONDITION_FAILED, 412",
            "SCIM_FILTER_INVALID, 400",
            "SCIM_PATCH_INVALID, 400",
            "SCIM_BAD_REQUEST, 400",
            "POLICY_INVALID, 400",
            "STORE_UNAVAILABLE, 503",
            "NATIVE_AUTH_INVALID_GRANT, 400"
    })
    void mapsErrorCodeToHttpStatus(String code, int expectedStatus) {
        ResponseEntity<Map<String, Object>> response = handler.handle(
                new IamException(IamErrorCode.valueOf(code), "boom"));

        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(response.getBody())
                .containsEntry("error", code)
                .containsEntry("message", "boom");
    }

    @Test
    void nullMessageBecomesEmptyString() {
        ResponseEntity<Map<String, Object>> response = handler.handle(
                new IamException(IamErrorCode.FORBIDDEN, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("message", "");
    }
}
