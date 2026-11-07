package cn.byteforce.seedj.starter.iam.scim;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScimExceptionHandlerTest {

    private final ScimExceptionHandler handler = new ScimExceptionHandler();

    @Test
    void mapsNotFoundCodesTo404WithoutScimType() {
        assertResponse(IamErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND, null);
        assertResponse(IamErrorCode.GROUP_NOT_FOUND, HttpStatus.NOT_FOUND, null);
    }

    @Test
    void mapsConflictCodesTo409Uniqueness() {
        assertResponse(IamErrorCode.USER_CONFLICT, HttpStatus.CONFLICT, "uniqueness");
        assertResponse(IamErrorCode.GROUP_CONFLICT, HttpStatus.CONFLICT, "uniqueness");
        assertResponse(IamErrorCode.SCIM_CONFLICT, HttpStatus.CONFLICT, "uniqueness");
    }

    @Test
    void mapsPreconditionAndAuthCodes() {
        assertResponse(IamErrorCode.SCIM_PRECONDITION_FAILED, HttpStatus.PRECONDITION_FAILED,
                "versionMismatch");
        assertResponse(IamErrorCode.SCIM_UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "invalidValue");
    }

    @Test
    void mapsScimValidationCodesTo400() {
        assertResponse(IamErrorCode.SCIM_FILTER_INVALID, HttpStatus.BAD_REQUEST, "invalidFilter");
        assertResponse(IamErrorCode.SCIM_PATCH_INVALID, HttpStatus.BAD_REQUEST, "invalidValue");
        assertResponse(IamErrorCode.SCIM_BAD_REQUEST, HttpStatus.BAD_REQUEST, "invalidValue");
        assertResponse(IamErrorCode.ROLE_NOT_FOUND, HttpStatus.BAD_REQUEST, "invalidValue");
    }

    private void assertResponse(IamErrorCode code, HttpStatus status, String scimType) {
        ResponseEntity<Map<String, Object>> response = handler.handle(
                new IamException(code, "detail for " + code));

        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo(ScimSupport.CONTENT_TYPE);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("schemas", List.of(ScimSupport.ERROR_SCHEMA))
                .containsEntry("detail", "detail for " + code)
                .containsEntry("status", String.valueOf(status.value()));
        if (scimType == null) {
            assertThat(body).doesNotContainKey("scimType");
        } else {
            assertThat(body).containsEntry("scimType", scimType);
        }
    }
}
