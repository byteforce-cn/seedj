package cn.byteforce.seedj.starter.iam.web;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** JSON error mapping for IAM management endpoints. SCIM has its own handler. */
@RestControllerAdvice
public class IamExceptionHandler {

    @ExceptionHandler(IamException.class)
    public ResponseEntity<Map<String, Object>> handle(IamException exception) {
        HttpStatus status = status(exception.getCode());
        return ResponseEntity.status(status).body(Map.of(
                "error", exception.getCode().name(),
                "message", exception.getMessage() == null ? "" : exception.getMessage()));
    }

    private static HttpStatus status(IamErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS, ACCOUNT_DISABLED, ACCOUNT_LOCKED, TOTP_INVALID,
                    TOTP_REPLAY, RECOVERY_CODE_INVALID, MFA_CHALLENGE_INVALID,
                    MFA_CHALLENGE_EXPIRED, MFA_CHALLENGE_LOCKED, SCIM_UNAUTHORIZED ->
                    HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case USER_NOT_FOUND, GROUP_NOT_FOUND, ROLE_NOT_FOUND, POLICY_NOT_FOUND,
                    NOT_FOUND, PERMISSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NATIVE_AUTH_INVALID_CLIENT -> HttpStatus.UNAUTHORIZED;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case USER_CONFLICT, GROUP_CONFLICT, ROLE_CONFLICT, CONFLICT, SCIM_CONFLICT ->
                    HttpStatus.CONFLICT;
            case SCIM_PRECONDITION_FAILED -> HttpStatus.PRECONDITION_FAILED;
            case SCIM_FILTER_INVALID, SCIM_PATCH_INVALID, SCIM_BAD_REQUEST, POLICY_INVALID ->
                    HttpStatus.BAD_REQUEST;
            case STORE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
