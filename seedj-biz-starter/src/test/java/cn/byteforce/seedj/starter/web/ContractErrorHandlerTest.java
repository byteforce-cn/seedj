package cn.byteforce.seedj.starter.web;

import cn.byteforce.seedj.core.InfrastructureErrorCode;
import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.CqrsErrorCode;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.domain.exception.AggregateNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 错误信封的冻结形状与状态码、Retry-After、traceId。
 */
class ContractErrorHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ContractErrorHandler())
                .build();
    }

    @Test
    @DisplayName("契约错误使用 code/message/details 信封")
    void contractErrorUsesFrozenEnvelope() throws Exception {
        mvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIS-404-001"))
                .andExpect(jsonPath("$.message").value("object not found"))
                .andExpect(jsonPath("$.details.reason_code").value("EIS-404-001"))
                .andExpect(jsonPath("$.details.message_key").value("OBJECT_NOT_FOUND"));
    }

    @Test
    @DisplayName("读模型未追平：409 + Retry-After + reason_code")
    void readModelNotReadyAddsRetryAfterAndReasonCode() throws Exception {
        mvc.perform(get("/probe/not-ready"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("EIS-409-003"))
                .andExpect(jsonPath("$.details.reason_code").value("EIS-409-003"))
                .andExpect(jsonPath("$.details.minVersion").value(2));
    }

    @Test
    @DisplayName("非法 JSON：400 且不泄露解析细节")
    void malformedBodyIs400InvalidPayload() throws Exception {
        mvc.perform(post("/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-001"))
                .andExpect(jsonPath("$.message").value("invalid request body"));
    }

    @Test
    @DisplayName("基础设施故障：503 且不泄露内部消息")
    void infrastructureFailureIs503WithoutInternalMessage() throws Exception {
        mvc.perform(get("/probe/infrastructure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EIS-503-001"))
                .andExpect(jsonPath("$.message").value("unavailable"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    @DisplayName("契约内部错误：500 + traceId，不泄露异常消息")
    void contractInternalErrorIsSanitized() throws Exception {
        mvc.perform(get("/probe/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("EIS-500-001"))
                .andExpect(jsonPath("$.message").value("internal error"))
                .andExpect(jsonPath("$.details.traceId").isNotEmpty())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    @DisplayName("CQRS 业务拒绝：400 EIS-400-002，不是 503")
    void cqrsCommandRejectedIs400() throws Exception {
        mvc.perform(get("/probe/command-rejected"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-002"))
                .andExpect(jsonPath("$.details.reason_code")
                        .value("CQRS-COMMAND_REJECTED"));
    }

    @Test
    @DisplayName("CQRS 乐观并发：409 EIS-409-002")
    void cqrsConcurrencyConflictIs409() throws Exception {
        mvc.perform(get("/probe/concurrency"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EIS-409-002"));
    }

    @Test
    @DisplayName("聚合不存在：404 EIS-404-001，带聚合细节")
    void aggregateNotFoundIs404() throws Exception {
        mvc.perform(get("/probe/aggregate-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIS-404-001"))
                .andExpect(jsonPath("$.details.repository").value("orders"))
                .andExpect(jsonPath("$.details.aggregate_id").value("o-1"));
    }

    @Test
    @DisplayName("CQRS 存储缺失：503")
    void cqrsStoreUnavailableIs503() throws Exception {
        mvc.perform(get("/probe/store-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EIS-503-001"));
    }

    @Test
    @DisplayName("未处理异常：500 + traceId，不泄露堆栈")
    void unexpectedExceptionIs500WithTraceId() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("EIS-500-001"))
                .andExpect(jsonPath("$.message").value("internal error"))
                .andExpect(jsonPath("$.details.traceId").isNotEmpty())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @RestController
    static final class ProbeController {

        @GetMapping("/probe/missing")
        Map<String, Object> missing() {
            throw new ContractException(ContractErrorCode.OBJECT_NOT_FOUND, "object not found");
        }

        @GetMapping("/probe/not-ready")
        Map<String, Object> notReady() {
            throw new ContractException(ContractErrorCode.READ_MODEL_NOT_READY,
                    "version not readable yet", Map.of("minVersion", 2), null);
        }

        @GetMapping("/probe/infrastructure")
        Map<String, Object> infrastructure() {
            throw new InfrastructureException(InfrastructureErrorCode.MIGRATION_FAILED,
                    "secret database detail");
        }

        @GetMapping("/probe/internal")
        Map<String, Object> internal() {
            throw new ContractException(ContractErrorCode.INTERNAL_ERROR,
                    "secret internal detail");
        }

        @GetMapping("/probe/command-rejected")
        Map<String, Object> commandRejected() {
            throw new CqrsException(CqrsErrorCode.COMMAND_REJECTED, "rejected by aggregate");
        }

        @GetMapping("/probe/concurrency")
        Map<String, Object> concurrency() {
            throw new CqrsException(CqrsErrorCode.CONCURRENCY_CONFLICT, "version moved");
        }

        @GetMapping("/probe/aggregate-missing")
        Map<String, Object> aggregateMissing() {
            throw new AggregateNotFoundException("orders", "o-1");
        }

        @GetMapping("/probe/store-unavailable")
        Map<String, Object> storeUnavailable() {
            throw new CqrsException(CqrsErrorCode.STORE_UNAVAILABLE, "no event store");
        }

        @GetMapping("/probe/boom")
        Map<String, Object> boom() {
            throw new IllegalStateException("secret stack detail");
        }

        @PostMapping(value = "/probe/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> body(@RequestBody Map<String, Object> body) {
            return body;
        }
    }
}
