package cn.byteforce.seedj.starter.ai.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 审批配置的默认值、解析与启动期校验。 */
class ApprovalPropertiesTest {

    private static final String APPROVER = "018f2e3a-0000-7000-8000-000000000118";

    @Test
    @DisplayName("默认关闭且通知开启")
    void defaultsAreOffAndSafe() {
        ApprovalProperties props =
                new ApprovalProperties(false, null, null, null, null, null, false, null, null);

        assertThat(props.enabled()).isFalse();
        assertThat(props.namespace()).isEqualTo("seedj-ai");
        assertThat(props.name()).isEqualTo("agent-approval");
        assertThat(props.version()).isEqualTo("1.0");
        assertThat(props.approvers()).isEmpty();
        assertThat(props.deadline()).isEqualTo(ApprovalProperties.DEFAULT_DEADLINE);
        assertThat(props.allowSelfApprove()).isFalse();
        assertThat(props.notification().enabled()).isTrue();
        assertThat(props.sweep()).isEqualTo(ApprovalProperties.DEFAULT_SWEEP);
    }

    @Test
    @DisplayName("approvers 里不是 UUID：点名配置键")
    void approverIdsRejectNonUuid() {
        ApprovalProperties props =
                new ApprovalProperties(
                        true, null, null, null, List.of("alice"), null, false, null, null);

        assertThatThrownBy(props::approverIds)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seedj.ai.approval.approvers");
    }

    @Test
    @DisplayName("approverIds 保留顺序并解析 UUID")
    void approverIdsParseUuids() {
        String second = "018f2e3a-0000-7000-8000-000000000119";
        ApprovalProperties props =
                new ApprovalProperties(
                        true, null, null, null, List.of(APPROVER, second), null, false, null, null);

        assertThat(props.approverIds())
                .containsExactly(UUID.fromString(APPROVER), UUID.fromString(second));
    }

    @Test
    @DisplayName("definition 解析 namespace/name 与 major.minor")
    void definitionParsesVersion() {
        ApprovalProperties props =
                new ApprovalProperties(
                        true, "ns", "appr", "2.3", List.of(APPROVER), null, false, null, null);

        assertThat(props.definitionId()).isEqualTo(new WorkflowDefinitionId("ns", "appr"));
        assertThat(props.definitionVersion()).isEqualTo(new WorkflowDefinitionVersion(2, 3));
    }

    @Test
    @DisplayName("非法版本格式：点名配置键")
    void invalidVersionNamesKey() {
        ApprovalProperties props =
                new ApprovalProperties(
                        true, null, null, "1", List.of(APPROVER), null, false, null, null);

        assertThatThrownBy(props::definitionVersion)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seedj.ai.approval.version");
    }

    @Test
    @DisplayName("deadline/sweep 非正：拒绝")
    void rejectsNonPositiveDurations() {
        assertThatThrownBy(
                        () ->
                                new ApprovalProperties(
                                        false,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        Duration.ZERO,
                                        false,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seedj.ai.approval.deadline");
        assertThatThrownBy(
                        () ->
                                new ApprovalProperties(
                                        false,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        false,
                                        null,
                                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seedj.ai.approval.sweep");
    }
}
