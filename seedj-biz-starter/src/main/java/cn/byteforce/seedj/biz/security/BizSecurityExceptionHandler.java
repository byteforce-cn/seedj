package cn.byteforce.seedj.biz.security;

import cn.byteforce.seedj.core.security.SecurityErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 控制器内抛出的认证/授权异常的 JSON 映射。
 *
 * <p>过滤器层的 401/403 由 {@link JsonAuthenticationEntryPoint} /
 * {@link JsonAccessDeniedHandler} 处理；这里覆盖的是方法级安全
 * （{@code @PreAuthorize}）在控制器调用链里抛出的情况——否则会落到
 * 全局异常处理，变成 500。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BizSecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException exception) {
        SecurityErrorCode code = SecurityErrorCode.ACCESS_DENIED;
        return ResponseEntity.status(code.httpStatus()).body(body(code, "access denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException exception) {
        SecurityErrorCode code = SecurityErrorCode.AUTHENTICATION_FAILED;
        return ResponseEntity.status(code.httpStatus()).body(body(code, "invalid credentials"));
    }

    private static Map<String, Object> body(SecurityErrorCode code, String message) {
        return Map.of("error", code.name(), "message", message);
    }
}
