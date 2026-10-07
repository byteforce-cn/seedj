package cn.byteforce.seedj.starter.web;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.core.exception.BaseException;
import cn.byteforce.seedj.web.core.ApiResponse;
import cn.byteforce.seedj.web.core.ResponseCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：把异常映射为统一的 {@link ApiResponse} 错误响应（seedj ADR-0002 契约）。
 *
 * <p>映射表：</p>
 * <ul>
 *   <li>{@link BaseException}：status/code/message 取异常自身（业务失败默认 HTTP 200）；</li>
 *   <li>参数校验失败（body/表单/方法级）→ 400 + {@code BAD_REQUEST}；</li>
 *   <li>请求体不可读、参数缺失、类型不匹配 → 400 + {@code BAD_REQUEST}；</li>
 *   <li>资源不存在 → 404；方法不支持 → 405；媒体类型不支持 → 415；</li>
 *   <li>未预期异常 → 500 + {@code INTERNAL_SERVER_ERROR}（消息不泄露内部细节，堆栈进日志）。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务/系统基类异常。
     *
     * @param ex 基类异常
     * @return 统一错误响应
     */
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleBaseException(BaseException ex) {
        int status = normalizeStatus(ex.getHttpStatus());
        if (status >= 500) {
            log.error("业务处理异常: code={}, message={}", ex.getCode(), ex.getMessage(), ex);
        } else {
            log.warn("业务处理失败: code={}, message={}", ex.getCode(), ex.getMessage());
        }
        return error(status, ex.getCode(), ex.getMessage());
    }

    /**
     * 处理 body / 表单参数绑定与校验失败（含 {@link MethodArgumentNotValidException}）。
     *
     * @param ex 绑定异常
     * @return 400 + 首个字段错误消息
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException ex) {
        String message = firstFieldError(ex.getBindingResult());
        log.warn("参数校验失败: {}", message);
        return error(400, ResponseCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理方法级参数校验失败（Spring 6.1+ {@code @Validated} 控制器）。
     *
     * @param ex 方法级校验异常
     * @return 400 + 首个校验错误消息
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidationException(HandlerMethodValidationException ex) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(ResponseCode.BAD_REQUEST.getMessage());
        log.warn("方法级参数校验失败: {}", message);
        return error(400, ResponseCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理 jakarta 校验约束违例（如 {@code @Validated} 服务层）。
     *
     * @param ex 约束违例异常
     * @return 400 + 首个违例消息
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ResponseCode.BAD_REQUEST.getMessage());
        log.warn("参数约束违例: {}", message);
        return error(400, ResponseCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理缺少必填请求参数。
     *
     * @param ex 参数缺失异常
     * @return 400
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameterException(MissingServletRequestParameterException ex) {
        String message = "缺少请求参数: " + ex.getParameterName();
        log.warn("{}", message);
        return error(400, ResponseCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理请求参数类型不匹配。
     *
     * @param ex 类型不匹配异常
     * @return 400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        String message = "参数类型不匹配: " + ex.getName();
        log.warn("{}", message);
        return error(400, ResponseCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理请求体不可读（JSON 语法错误、字段类型错误、未知 code 等）。
     *
     * @param ex 请求体解析异常
     * @return 400（响应消息使用统一文案，细节进 debug 日志）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadableException(
            HttpMessageNotReadableException ex) {
        log.debug("请求体解析失败", ex);
        return error(400, ResponseCode.BAD_REQUEST.getCode(), ResponseCode.BAD_REQUEST.getMessage());
    }

    /**
     * 处理资源不存在。
     *
     * @param ex 资源不存在异常
     * @return 404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException ex) {
        log.warn("资源不存在: {}", ex.getResourcePath());
        return error(404, ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getMessage());
    }

    /**
     * 处理请求方法不支持。
     *
     * @param ex 方法不支持异常
     * @return 405
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持: {}", ex.getMethod());
        return error(405, ResponseCode.METHOD_NOT_ALLOWED.getCode(),
                ResponseCode.METHOD_NOT_ALLOWED.getMessage());
    }

    /**
     * 处理请求媒体类型不支持。
     *
     * @param ex 媒体类型异常
     * @return 415
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException ex) {
        log.warn("请求媒体类型不支持: {}", ex.getContentType());
        return error(415, ResponseCode.UNSUPPORTED_MEDIA_TYPE.getCode(),
                ResponseCode.UNSUPPORTED_MEDIA_TYPE.getMessage());
    }

    /**
     * 兜底处理未预期异常（消息不泄露内部细节，堆栈进 error 日志）。
     *
     * @param ex 未预期异常
     * @return 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        log.error("未捕获异常", ex);
        return error(500, ResponseCode.INTERNAL_SERVER_ERROR.getCode(),
                ResponseCode.INTERNAL_SERVER_ERROR.getMessage());
    }

    private static String firstFieldError(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ResponseCode.BAD_REQUEST.getMessage());
    }

    private static int normalizeStatus(int httpStatus) {
        return (httpStatus >= 100 && httpStatus <= 599) ? httpStatus : BaseException.DEFAULT_HTTP_STATUS;
    }

    private static ResponseEntity<ApiResponse<Void>> error(int status, int code, String message) {
        ApiResponse<Void> body = ApiResponse.error(code, message, RequestContext.traceId().orElse(null));
        return ResponseEntity.status(status).body(body);
    }
}
