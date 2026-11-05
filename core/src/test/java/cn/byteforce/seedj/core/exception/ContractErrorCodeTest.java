package cn.byteforce.seedj.core.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约错误码目录的冻结格式与信封映射。
 */
class ContractErrorCodeTest {

    @Test
    void everyCodeMatchesFrozenFormatAndStatusPrefix() {
        for (ContractErrorCode code : ContractErrorCode.values()) {
            assertThat(code.getCode()).matches("EIS-\\d{3}-\\d{3}");
            assertThat(code.getCode()).startsWith("EIS-" + code.httpStatus() + "-");
        }
    }

    @Test
    void codesAreUniqueAndComplete() {
        List<String> codes = Arrays.stream(ContractErrorCode.values())
                .map(ContractErrorCode::getCode)
                .toList();
        assertThat(codes)
                .doesNotHaveDuplicates()
                .hasSize(12)
                .contains("EIS-400-001", "EIS-400-003", "EIS-403-002",
                        "EIS-404-001", "EIS-409-003", "EIS-503-001");
    }

    @Test
    void exceptionCarriesReasonAndDetailsThroughEnvelope() {
        ContractException e = new ContractException(ContractErrorCode.READ_MODEL_NOT_READY,
                "version 5 not readable yet", Map.<String, Object>of("required", 5), null);
        ApiError error = ApiError.of(e);
        assertThat(error.code()).isEqualTo("EIS-409-003");
        assertThat(error.message()).isEqualTo("version 5 not readable yet");
        assertThat(error.details()).containsEntry("required", 5);
    }
}
