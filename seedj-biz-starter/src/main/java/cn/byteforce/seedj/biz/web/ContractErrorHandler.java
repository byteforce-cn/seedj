package cn.byteforce.seedj.biz.web;

import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.core.exception.ApiError;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.lock.LockException;
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
     * CQRS 业务/并发错误 → 契约状态码。
     *
     * <p>业务拒绝是 400、乐观并发是 409、存储缺失是 503，其余是 500；不得统一压成 503。</p>
     *
     * @param error CQRS 异常
     * @return 契约信封
     */
    @ExceptionHandler(CqrsException.class)
    public ResponseEntity<ApiError> handleCqrs(CqrsException error) {
        ContractErrorCode code = switch (error.getErrorCode()) {
            case COMMAND_REJECTED -> ContractErrorCode.COMMAND_REJECTED;
            case CONCURRENCY_CONFLICT -> ContractErrorCode.VERSION_CONFLICT;
            case INVALID_STREAM_ID, INVALID_ID -> ContractErrorCode.INVALID_PAYLOAD;
            case STORE_UNAVAILABLE -> ContractErrorCode.UNAVAILABLE;
            default -> ContractErrorCode.INTERNAL_ERROR;
        };
        if (code == ContractErrorCode.INTERNAL_ERROR) {
            String traceId = UUID.randomUUID().toString();
            log.error("seedj: cqrs internal error traceId={} code={}", traceId,
                    error.getErrorCode(), error);
            return response(code, "internal error", Map.of("traceId", traceId));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason_code", "CQRS-" + error.getErrorCode().name());
        return response(code, error.getMessage(), details);
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
     * 锁冲突/锁服务故障 → 可重试的 409（带 {@code Retry-After}）或 503。
     *
     * <p>{@code LOCK_BUSY} 表示同对象已有命令在执行，调用方退避后重试即可；
     * 不得落进通用基础设施 503，否则调用方会把可重试冲突当成依赖故障。</p>
     *
     * @param error 锁异常
     * @return 409/503/500 信封
     */
    @ExceptionHandler(LockException.class)
    public ResponseEntity<ApiError> handleLock(LockException error) {
        return switch (error.getErrorCode()) {
            case LOCK_BUSY -> {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.RETRY_AFTER, "1");
                yield new ResponseEntity<>(new ApiError(
                        ContractErrorCode.VERSION_CONFLICT.getCode(), "resource busy",
                        Map.of("reason_code", "LOCK-BUSY")), headers, HttpStatus.CONFLICT);
            }
            case PROVIDER_UNAVAILABLE -> response(ContractErrorCode.UNAVAILABLE, "unavailable",
                    Map.of("reason_code", "LOCK-PROVIDER-UNAVAILABLE"));
            default -> {
                String traceId = UUID.randomUUID().toString();
                log.error("seedj: lock internal error traceId={} code={}", traceId,
                        error.getErrorCode(), error);
                yield response(ContractErrorCode.INTERNAL_ERROR, "internal error",
                        Map.of("traceId", traceId,
                                "reason_code", "LOCK-" + error.getErrorCode().name()));
            }
        };
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
