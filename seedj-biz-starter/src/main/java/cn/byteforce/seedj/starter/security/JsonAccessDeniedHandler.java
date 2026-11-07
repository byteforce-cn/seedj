package cn.byteforce.seedj.starter.security;

import cn.byteforce.seedj.core.exception.ApiError;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.security.SecurityErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 403 响应体：契约错误信封，固定 {@code EIS-403-001}，details 带 reason。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper json;
    private final SecurityDenialAudit audit;

    public JsonAccessDeniedHandler(JsonMapper json, SecurityDenialAudit audit) {
        this.json = json;
        this.audit = audit;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        SecurityErrorCode securityCode = SecurityErrorCode.ACCESS_DENIED;
        ContractErrorCode contractCode = ContractErrorCode.FORBIDDEN;
        response.setStatus(contractCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), new ApiError(contractCode.getCode(),
                "access denied", Map.of("reason", "access denied")));
        audit.recordDenial(request, securityCode);
    }
}
