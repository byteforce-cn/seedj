package cn.byteforce.seedj.starter.iam.web;

import cn.byteforce.seedj.core.exception.ApiError;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * IAM 管理端点的错误映射：与冻结信封一致，固定 {@code {code,message,details}}。
 *
 * <p>{@code code} 是稳定的 {@code IAM-<枚举名>}，{@code details.reason_code} 保留枚举名供
 * 调用方分支。SCIM 与 OAuth2 token 端点例外，分别使用 RFC 7644 与 RFC 6749 的错误形状，见
 * {@code docs/guide/errors.md}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestControllerAdvice
public class IamExceptionHandler {

    /**
     * IAM 错误 → 契约信封。
     *
     * @param exception IAM 错误
     * @return 错误响应
     */
    @ExceptionHandler(IamException.class)
    public ResponseEntity<ApiError> handle(IamException exception) {
        IamErrorCode code = exception.getCode();
        HttpStatus status = status(code);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason_code", code.name());
        details.put("message_key", code.name());
        return ResponseEntity.status(status)
                .body(
                        new ApiError(
                                "IAM-" + code.name(),
                                exception.getMessage() == null ? "" : exception.getMessage(),
                                details));
    }

    private static HttpStatus status(IamErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS,
                            ACCOUNT_DISABLED,
                            ACCOUNT_LOCKED,
                            TOTP_INVALID,
                            TOTP_REPLAY,
                            TOTP_REQUIRED,
                            RECOVERY_CODE_INVALID,
                            MFA_CHALLENGE_INVALID,
                            MFA_CHALLENGE_EXPIRED,
                            MFA_CHALLENGE_LOCKED,
                            SCIM_UNAUTHORIZED,
                            NATIVE_AUTH_INVALID_CLIENT ->
                    HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case USER_NOT_FOUND,
                            GROUP_NOT_FOUND,
                            ROLE_NOT_FOUND,
                            POLICY_NOT_FOUND,
                            NOT_FOUND,
                            PERMISSION_NOT_FOUND ->
                    HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case USER_CONFLICT,
                            GROUP_CONFLICT,
                            ROLE_CONFLICT,
                            CONFLICT,
                            SCIM_CONFLICT,
                            TOTP_ALREADY_ENROLLED ->
                    HttpStatus.CONFLICT;
            case SCIM_PRECONDITION_FAILED -> HttpStatus.PRECONDITION_FAILED;
            case SCIM_FILTER_INVALID,
                            SCIM_PATCH_INVALID,
                            SCIM_BAD_REQUEST,
                            POLICY_INVALID,
                            TOTP_NOT_ENROLLED,
                            NATIVE_AUTH_DISABLED,
                            NATIVE_AUTH_INVALID_GRANT ->
                    HttpStatus.BAD_REQUEST;
            case STORE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
