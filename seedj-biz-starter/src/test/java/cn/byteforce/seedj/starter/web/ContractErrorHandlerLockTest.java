package cn.byteforce.seedj.starter.web;

import cn.byteforce.seedj.lock.LockErrorCode;
import cn.byteforce.seedj.lock.LockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 锁异常的错误信封：可重试冲突与依赖故障必须区分。 */
class ContractErrorHandlerLockTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new LockProbeController())
                .setControllerAdvice(new ContractErrorHandler())
                .build();
    }

    @Test
    @DisplayName("锁被占用：409 + Retry-After + reason_code，不是 503")
    void lockBusyIsRetryableConflict() throws Exception {
        mvc.perform(get("/probe/lock-busy"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("EIS-409-002"))
                .andExpect(jsonPath("$.message").value("resource busy"))
                .andExpect(jsonPath("$.details.reason_code").value("LOCK-BUSY"));
    }

    @Test
    @DisplayName("锁服务不可用：503 + 明确 reason_code")
    void lockProviderUnavailableIs503() throws Exception {
        mvc.perform(get("/probe/lock-provider"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EIS-503-001"))
                .andExpect(jsonPath("$.details.reason_code")
                        .value("LOCK-PROVIDER-UNAVAILABLE"));
    }

    @Test
    @DisplayName("非法锁键：500 + traceId，不泄露内部细节")
    void invalidLockKeyIsInternalError() throws Exception {
        mvc.perform(get("/probe/lock-invalid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("EIS-500-001"))
                .andExpect(jsonPath("$.details.traceId").isNotEmpty())
                .andExpect(jsonPath("$.details.reason_code").value("LOCK-INVALID_KEY"));
    }

    @RestController
    static final class LockProbeController {

        @GetMapping("/probe/lock-busy")
        Map<String, Object> lockBusy() {
            throw new LockException(LockErrorCode.LOCK_BUSY, "employee:e-1 is locked");
        }

        @GetMapping("/probe/lock-provider")
        Map<String, Object> lockProvider() {
            throw new LockException(LockErrorCode.PROVIDER_UNAVAILABLE, "redis down");
        }

        @GetMapping("/probe/lock-invalid")
        Map<String, Object> lockInvalid() {
            throw new LockException(LockErrorCode.INVALID_KEY, "blank key");
        }
    }
}
