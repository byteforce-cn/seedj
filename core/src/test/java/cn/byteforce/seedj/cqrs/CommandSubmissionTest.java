package cn.byteforce.seedj.cqrs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令提交：类型、幂等键与目标列表在构造期冻结。
 */
class CommandSubmissionTest {

    private static final UUID ISSUER = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    @Test
    @DisplayName("目标列表可空，缺省为空列表且载荷不可变")
    void normalisesTargetsAndPayload() {
        CommandSubmission submission = new CommandSubmission("hr.employee.update",
                Map.of("employee_no", "E-1"), "key-1", null, ISSUER);

        assertThat(submission.targets()).isEmpty();
        assertThat(submission.issuer()).isEqualTo(ISSUER);
        assertThatThrownBy(() -> submission.payload().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("非法类型、空白幂等键与超长键被拒绝")
    void rejectsIllegalKeyAndType() {
        assertThatThrownBy(() -> new CommandSubmission("Hr.Employee", Map.of(), "key-1",
                List.of(), ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal command type");
        assertThatThrownBy(() -> new CommandSubmission("hr.employee.update", Map.of(), " ",
                List.of(), ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey must not be blank");
        assertThatThrownBy(() -> new CommandSubmission("hr.employee.update", Map.of(),
                "k".repeat(256), List.of(), ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longer than 255");
    }

    @Test
    @DisplayName("目标列表不得包含 null，issuer 必填")
    void rejectsNullTargetsAndIssuer() {
        assertThatThrownBy(() -> new CommandSubmission("hr.employee.update", Map.of(), "key-1",
                java.util.Arrays.asList((UUID) null), ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targets must not contain null");
        assertThatThrownBy(() -> new CommandSubmission("hr.employee.update", Map.of(), "key-1",
                List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("issuer");
    }
}
