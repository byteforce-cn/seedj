package cn.byteforce.seedj.cqrs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹必须与参考内核的规范化 JSON 算法逐位一致。
 */
class IdempotencyFingerprintTest {

    private static final UUID TARGET = UUID.fromString("01900000-0000-7000-8000-000000000201");

    @Test
    @DisplayName("与参考内核同一输入的摘要逐位一致")
    void matchesReferenceKernelForSameInput() {
        Map<String, Object> payload = Map.of(
                "employee_id", "01900000-0000-7000-8000-000000000101",
                "name", "Alice");

        assertThat(IdempotencyFingerprint.of("hr.employee.onboard", payload, List.of(TARGET)))
                .isEqualTo("fe097238cca38f8806ba9fe20856a001b2cc0ada9bc319df64f9048d6fd33cb4");
    }

    @Test
    @DisplayName("载荷键序不影响摘要")
    void ignoresPayloadKeyOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "Alice");
        first.put("employee_id", "01900000-0000-7000-8000-000000000101");

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("employee_id", "01900000-0000-7000-8000-000000000101");
        second.put("name", "Alice");

        assertThat(IdempotencyFingerprint.of("hr.employee.onboard", first, List.of(TARGET)))
                .isEqualTo(IdempotencyFingerprint.of("hr.employee.onboard", second, List.of(TARGET)));
    }

    @Test
    @DisplayName("targets 顺序参与指纹，与内核保持一致")
    void includesTargetOrder() {
        UUID other = UUID.fromString("01900000-0000-7000-8000-000000000202");

        assertThat(IdempotencyFingerprint.of("hr.employee.onboard", Map.of("name", "Alice"),
                List.of(TARGET, other)))
                .isNotEqualTo(IdempotencyFingerprint.of("hr.employee.onboard",
                        Map.of("name", "Alice"), List.of(other, TARGET)));
    }

    @Test
    @DisplayName("类型或载荷变化会改变摘要")
    void changesWhenTypeOrPayloadChanges() {
        String base = IdempotencyFingerprint.of("hr.employee.onboard", Map.of("name", "Alice"),
                List.of(TARGET));

        assertThat(IdempotencyFingerprint.of("hr.employee.transfer", Map.of("name", "Alice"),
                List.of(TARGET))).isNotEqualTo(base);
        assertThat(IdempotencyFingerprint.of("hr.employee.onboard", Map.of("name", "Bob"),
                List.of(TARGET))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("接受 CommandSubmission 的便捷入口与显式参数一致")
    void acceptsSubmission() {
        CommandSubmission submission = new CommandSubmission("hr.employee.onboard",
                Map.of("name", "Alice"), "key-1", List.of(TARGET),
                UUID.fromString("01900000-0000-7000-8000-000000000101"));

        assertThat(IdempotencyFingerprint.of(submission))
                .isEqualTo(IdempotencyFingerprint.of("hr.employee.onboard",
                        Map.of("name", "Alice"), List.of(TARGET)));
    }
}
