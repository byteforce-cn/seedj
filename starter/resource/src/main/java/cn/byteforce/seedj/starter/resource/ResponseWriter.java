package cn.byteforce.seedj.starter.resource;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * 统一错误体写出工具：固定 JSON content-type，序列化 {@code ApiResponse} 到响应体。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ResponseWriter {

    /**
     * JSON content-type（含字符集）。
     */
    static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    private ResponseWriter() {
    }

    /**
     * 写出统一错误体。
     *
     * @param jsonMapper JSON 序列化器
     * @param response   HTTP 响应
     * @param body       响应体
     * @throws IOException 写出失败
     */
    static void write(JsonMapper jsonMapper, HttpServletResponse response, Object body) throws IOException {
        response.setContentType(JSON_CONTENT_TYPE);
        jsonMapper.writeValue(response.getWriter(), body);
    }
}
