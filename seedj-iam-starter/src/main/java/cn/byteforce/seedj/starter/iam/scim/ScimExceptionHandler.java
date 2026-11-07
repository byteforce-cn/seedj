package cn.byteforce.seedj.starter.iam.scim;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** SCIM-specific error envelope ({@code scimType} + {@code status}). */
@RestControllerAdvice(basePackages = "cn.byteforce.seedj.starter.iam.scim")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScimExceptionHandler {

    private static final MediaType SCIM = MediaType.parseMediaType(ScimSupport.CONTENT_TYPE);

    @ExceptionHandler(IamException.class)
    public ResponseEntity<Map<String, Object>> handle(IamException exception) {
        int status = status(exception.getCode());
        String scimType = scimType(exception.getCode());
        return ResponseEntity.status(status)
                .contentType(SCIM)
                .body(ScimSupport.error(exception.getMessage(), status, scimType));
    }

    private static int status(IamErrorCode code) {
        return switch (code) {
            case USER_NOT_FOUND, GROUP_NOT_FOUND -> 404;
            case USER_CONFLICT, GROUP_CONFLICT, SCIM_CONFLICT -> 409;
            case SCIM_PRECONDITION_FAILED -> 412;
            case SCIM_UNAUTHORIZED -> 401;
            case SCIM_FILTER_INVALID, SCIM_PATCH_INVALID, SCIM_BAD_REQUEST -> 400;
            default -> 400;
        };
    }

    private static String scimType(IamErrorCode code) {
        return switch (code) {
            case SCIM_FILTER_INVALID -> "invalidFilter";
            case SCIM_PATCH_INVALID -> "invalidValue";
            case SCIM_PRECONDITION_FAILED -> "versionMismatch";
            case USER_CONFLICT, GROUP_CONFLICT, SCIM_CONFLICT -> "uniqueness";
            case USER_NOT_FOUND, GROUP_NOT_FOUND -> null;
            default -> "invalidValue";
        };
    }
}
