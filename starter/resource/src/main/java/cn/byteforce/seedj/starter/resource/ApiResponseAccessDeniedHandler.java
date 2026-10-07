package cn.byteforce.seedj.starter.resource;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.web.core.ApiResponse;
import cn.byteforce.seedj.web.core.ResponseCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * 403 响应器：已认证但无权限的请求输出统一错误体（{@link ApiResponse}）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class ApiResponseAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    /**
     * 构造响应器。
     *
     * @param jsonMapper JSON 序列化器（沿用 seedj JSON 契约）
     */
    public ApiResponseAccessDeniedHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        ResponseWriter.write(jsonMapper, response,
                ApiResponse.error(ResponseCode.FORBIDDEN.getCode(),
                        ResponseCode.FORBIDDEN.getMessage(),
                        RequestContext.traceId().orElse(null)));
    }
}
