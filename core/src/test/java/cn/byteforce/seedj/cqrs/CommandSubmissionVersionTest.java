package cn.byteforce.seedj.cqrs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 命令信封携带 expected version。 */
class CommandSubmissionVersionTest {

    @Test
    @DisplayName("submission 携带 expectedVersion")
    void submissionCarriesExpectedVersion() {
        CommandSubmission submission = new CommandSubmission("hr.employee.update",
                Map.of(), "k-1", List.of(), UUID.randomUUID(), 3L);

        assertThat(submission.expectedVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("旧构造器不校验版本")
    void legacyConstructorHasNoExpectedVersion() {
        CommandSubmission submission = new CommandSubmission("hr.employee.update",
                Map.of(), "k-1", List.of(), UUID.randomUUID());

        assertThat(submission.expectedVersion()).isNull();
    }

    @Test
    @DisplayName("负数版本在构造期失败")
    void negativeExpectedVersionIsRejected() {
        assertThatThrownBy(() -> new CommandSubmission("hr.employee.update", Map.of(), "k-1",
                List.of(), UUID.randomUUID(), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedVersion");
    }
}
