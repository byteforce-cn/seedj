package cn.byteforce.seedj.starter.resource;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.web.core.ApiResponse;
import cn.byteforce.seedj.web.core.ResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 安全异常 MVC 侧处理器：把方法级安全抛出的 {@link AccessDeniedException}
 * （如 {@code @PreAuthorize} 拒绝）与 {@link AuthenticationException} 映射为统一错误体。
 *
 * <p>说明：安全链内的拒绝由 {@link ApiResponseAccessDeniedHandler} 处理；本处理器覆盖
 * 「异常已进入 MVC 异常解析」的路径，避免被全局兜底处理器误映射为 500。
 * 排序取最高优先级，确保精确匹配先于兜底处理器。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SeedjSecurityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SeedjSecurityExceptionHandler.class);

    /**
     * 处理访问拒绝（403）。
     *
     * @param ex 访问拒绝异常
     * @return 403 + 统一错误体
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("访问被拒绝: {}", ex.getMessage());
        return ResponseEntity.status(403).body(ApiResponse.error(
                ResponseCode.FORBIDDEN.getCode(),
                ResponseCode.FORBIDDEN.getMessage(),
                RequestContext.traceId().orElse(null)));
    }

    /**
     * 处理认证失败（401，MVC 侧）。
     *
     * @param ex 认证异常
     * @return 401 + 统一错误体
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException ex) {
        log.warn("认证失败: {}", ex.getMessage());
        return ResponseEntity.status(401)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(ApiResponse.error(
                        ResponseCode.UNAUTHORIZED.getCode(),
                        ResponseCode.UNAUTHORIZED.getMessage(),
                        RequestContext.traceId().orElse(null)));
    }
}
