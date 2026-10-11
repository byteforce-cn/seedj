package cn.byteforce.seedj.starter.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理契约测试（HTTP 状态 / 错误码 / 消息 / traceId 全链路）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@SpringBootTest(classes = WebTestApplication.class)
@AutoConfigureMockMvc
class WebErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successResponseCarriesCodeZeroAndData() throws Exception {
        mockMvc.perform(get("/ok"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data").value("hello"));
    }

    @Test
    void bizExceptionMapsToHttp200WithBusinessCode() throws Exception {
        mockMvc.perform(get("/biz").header("X-Trace-Id", "fixed-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "fixed-123"))
                .andExpect(jsonPath("$.code").value(10007))
                .andExpect(jsonPath("$.message").value("库存不足"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value("fixed-123"));
    }

    @Test
    void validationFailureMapsTo400WithFieldMessage() throws Exception {
        mockMvc.perform(post("/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("名称不能为空"));
    }

    @Test
    void unknownPathMapsTo404() throws Exception {
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void unsupportedMethodMapsTo405() throws Exception {
        mockMvc.perform(post("/ok"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(10005));
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(20000))
                .andExpect(jsonPath("$.message").value("系统内部错误"))
                .andExpect(content().string(not(containsString("内部细节"))));
    }

    @Test
    void remoteExceptionMapsTo503ByDefault() throws Exception {
        mockMvc.perform(get("/remote"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(20001))
                .andExpect(jsonPath("$.message").value("依赖服务不可用"));
    }

    @Test
    void methodLevelValidationMapsTo400() throws Exception {
        mockMvc.perform(get("/named").param("q", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("q 不能为空"));
    }

    @Test
    void missingRequestParameterMapsTo400() throws Exception {
        mockMvc.perform(get("/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("缺少请求参数: q"));
    }

    @Test
    void typeMismatchMapsTo400() throws Exception {
        mockMvc.perform(get("/number").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("参数类型不匹配: page"));
    }

    @Test
    void unreadableBodyMapsTo400() throws Exception {
        mockMvc.perform(post("/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("请求参数错误"));
    }

    @Test
    void unsupportedMediaTypeMapsTo415() throws Exception {
        mockMvc.perform(post("/validated")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(10009))
                .andExpect(jsonPath("$.message").value("请求媒体类型不支持"));
    }

    @Test
    void notAcceptableMapsTo406() throws Exception {
        mockMvc.perform(get("/ok").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value(10010))
                .andExpect(jsonPath("$.message").value("响应媒体类型不可接受"));
    }

    @Test
    void uploadLimitMapsTo413() throws Exception {
        mockMvc.perform(get("/oversize"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(10011))
                .andExpect(jsonPath("$.message").value("请求体过大"));
    }

    @Test
    void serviceConstraintViolationMapsTo400() throws Exception {
        mockMvc.perform(get("/svc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("code 不能为空"));
    }
}
