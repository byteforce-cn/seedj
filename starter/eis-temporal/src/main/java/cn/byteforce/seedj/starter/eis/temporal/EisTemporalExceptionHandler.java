package cn.byteforce.seedj.starter.eis.temporal;

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
 * 时点查询端点异常处理：EIS 错误体（{@code code + message}），仅作用于
 * {@link EisTemporalController}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestControllerAdvice(assignableTypes = EisTemporalController.class)
public class EisTemporalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(EisTemporalExceptionHandler.class);

    /**
     * 处理 EIS 请求级异常。
     *
     * @param ex 请求异常
     * @return 契约错误体
     */
    @ExceptionHandler(EisRequestException.class)
    public ResponseEntity<Map<String, Object>> handleRequestException(EisRequestException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    /**
     * 处理未预期异常（EIS-500-001）。
     *
     * @param ex 异常
     * @return 500 契约错误体
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("EIS 时点查询未预期异常", ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "EIS-500-001");
        body.put("message", "服务内部错误");
        RequestContext.traceId().ifPresent(traceId -> body.put("trace_id", traceId));
        return ResponseEntity.status(500).body(body);
    }
}
