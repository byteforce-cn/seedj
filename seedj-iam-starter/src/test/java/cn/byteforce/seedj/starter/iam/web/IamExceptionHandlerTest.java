package cn.byteforce.seedj.starter.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.core.exception.ApiError;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** IAM 管理端点的错误码映射与冻结信封形状。 */
class IamExceptionHandlerTest {

    private final IamExceptionHandler handler = new IamExceptionHandler();

    @ParameterizedTest
    @EnumSource(IamErrorCode.class)
    void everyCodeMapsToFrozenEnvelope(IamErrorCode code) {
        ResponseEntity<ApiError> response = handler.handle(new IamException(code, "boom"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IAM-" + code.name());
        assertThat(response.getBody().message()).isEqualTo("boom");
        assertThat(response.getBody().details())
                .containsEntry("reason_code", code.name())
                .containsEntry("message_key", code.name());
    }

    @ParameterizedTest
    @CsvSource({
        "INVALID_CREDENTIALS, 401",
        "ACCOUNT_DISABLED, 401",
        "ACCOUNT_LOCKED, 401",
        "TOTP_INVALID, 401",
        "TOTP_REPLAY, 401",
        "TOTP_REQUIRED, 401",
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
        "TOTP_ALREADY_ENROLLED, 409",
        "SCIM_PRECONDITION_FAILED, 412",
        "SCIM_FILTER_INVALID, 400",
        "SCIM_PATCH_INVALID, 400",
        "SCIM_BAD_REQUEST, 400",
        "POLICY_INVALID, 400",
        "TOTP_NOT_ENROLLED, 400",
        "NATIVE_AUTH_DISABLED, 400",
        "STORE_UNAVAILABLE, 503",
        "NATIVE_AUTH_INVALID_GRANT, 400"
    })
    void mapsErrorCodeToHttpStatus(String code, int expectedStatus) {
        ResponseEntity<ApiError> response =
                handler.handle(new IamException(IamErrorCode.valueOf(code), "boom"));

        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
    }

    @Test
    void nullMessageBecomesEmptyString() {
        ResponseEntity<ApiError> response =
                handler.handle(new IamException(IamErrorCode.FORBIDDEN, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEmpty();
    }

    @Test
    void wireShapeIsCodeMessageDetails() throws Exception {
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new FailingController())
                        .setControllerAdvice(handler)
                        .build();

        mvc.perform(get("/iam-test/fail"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("IAM-FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("nope"))
                .andExpect(jsonPath("$.details.reason_code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @RestController
    static class FailingController {

        @GetMapping("/iam-test/fail")
        void fail() {
            throw new IamException(IamErrorCode.FORBIDDEN, "nope");
        }
    }
}
