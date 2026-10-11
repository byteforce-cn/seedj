package cn.byteforce.seedj.starter.eis.core.web;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.starter.eis.core.EisRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EIS 命令端点异常处理：把传输面异常映射为 EIS 错误体
 * （{@code code + message}；错误码目录 {@code 02-operation-contract/error-codes.yaml}）。
 *
 * <p>仅作用于 {@link EisCommandController}（{@code assignableTypes}），
 * 不干扰应用其他端点的 seedj 统一错误契约（ADR-0002）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestControllerAdvice(assignableTypes = EisCommandController.class)
public class EisCommandExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(EisCommandExceptionHandler.class);

    /**
     * 处理 EIS 请求级异常。
     *
     * @param ex 请求异常
     * @return 契约错误体（code + message）
     */
    @ExceptionHandler(EisRequestException.class)
    public ResponseEntity<Map<String, Object>> handleRequestException(EisRequestException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    /**
     * 处理未预期异常（EIS-500-001；不泄露内部细节，附 traceId 供排查）。
     *
     * @param ex 异常
     * @return 500 契约错误体
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("EIS 命令端点未预期异常", ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "EIS-500-001");
        body.put("message", "服务内部错误");
        RequestContext.traceId().ifPresent(traceId -> body.put("trace_id", traceId));
        return ResponseEntity.status(500).body(body);
    }
}
