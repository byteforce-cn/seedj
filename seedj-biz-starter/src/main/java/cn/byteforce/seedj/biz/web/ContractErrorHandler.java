package cn.byteforce.seedj.biz.web;

import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.core.exception.ApiError;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 契约面错误信封：所有错误响应固定 {@code {code,message,details?}}。
 *
 * <p>业务拒绝（命令）与 401/403 由各自边界处理，本类覆盖查询、参数解析、
 * 基础设施故障与未处理异常。{@code reason_code} 优先取异常携带的细分原因，
 * 为空时回落到错误码本身；{@code EIS-409-003} 必须带 {@code Retry-After}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ContractErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ContractErrorHandler.class);

    /**
     * 契约错误 → 冻结状态码 + 信封。
     *
     * @param error 契约错误
     * @return 错误响应
     */
    @ExceptionHandler(ContractException.class)
    public ResponseEntity<ApiError> handleContract(ContractException error) {
        if (error.code() == ContractErrorCode.INTERNAL_ERROR) {
            String traceId = UUID.randomUUID().toString();
            log.error("seedj: contract internal error traceId={}", traceId, error);
            return response(ContractErrorCode.INTERNAL_ERROR, "internal error",
                    Map.of("traceId", traceId));
        }
        Map<String, Object> details = new LinkedHashMap<>(error.details());
        String reasonCode = error.reasonCode() == null
                ? error.code().getCode() : error.reasonCode();
        details.putIfAbsent("reason_code", reasonCode);

        HttpHeaders headers = new HttpHeaders();
        if (error.code() == ContractErrorCode.READ_MODEL_NOT_READY) {
            headers.set(HttpHeaders.RETRY_AFTER, "1");
        }
        return new ResponseEntity<>(new ApiError(error.code().getCode(), error.reason(), details),
                headers, HttpStatus.valueOf(error.code().httpStatus()));
    }

    /**
     * 请求体/参数解析失败。
     *
     * @param error 解析异常
     * @return 400 信封
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception error) {
        String message = "invalid request";
        Map<String, Object> details = new LinkedHashMap<>();
        if (error instanceof HttpMessageNotReadableException) {
            message = "invalid request body";
        } else if (error instanceof MissingServletRequestParameterException missing) {
            message = "invalid request parameter";
            details.put("parameter", missing.getParameterName());
        } else if (error instanceof MethodArgumentTypeMismatchException mismatch) {
            message = "invalid request parameter";
            details.put("parameter", mismatch.getName());
        }
        return response(ContractErrorCode.INVALID_PAYLOAD, message, details);
    }

    /**
     * 存储/事件通道不可用。
     *
     * @param error 基础设施异常
     * @return 503 信封
     */
    @ExceptionHandler({InfrastructureException.class, DataAccessException.class})
    public ResponseEntity<ApiError> handleUnavailable(Exception error) {
        log.warn("seedj: infrastructure unavailable: {}", error.getClass().getSimpleName());
        return response(ContractErrorCode.UNAVAILABLE, "unavailable", Map.of());
    }

    /**
     * 未处理异常：不泄露内部细节，带 traceId。
     *
     * @param error 未处理异常
     * @return 500 信封
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception error) {
        String traceId = UUID.randomUUID().toString();
        log.error("seedj: unexpected error traceId={}", traceId, error);
        return response(ContractErrorCode.INTERNAL_ERROR, "internal error",
                Map.of("traceId", traceId));
    }

    private static ResponseEntity<ApiError> response(ContractErrorCode code, String message,
                                                     Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>(details);
        body.putIfAbsent("reason_code", code.getCode());
        return ResponseEntity.status(code.httpStatus())
                .body(new ApiError(code.getCode(), message, body));
    }
}
