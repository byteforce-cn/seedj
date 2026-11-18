package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 审批快照的构造口径：已批准就必须能回答“谁在什么时候批的”。
 *
 * <p>只有 {@link ApprovalStatus#APPROVED} 强制批准人与批准时间；未通过的结论不需要， 因为守卫看的是状态而不是这两个字段。
 */
class ApprovalSnapshotTest {

    private static final UUID APPROVER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant REQUESTED_AT = Instant.parse("2026-11-18T00:00:00Z");
    private static final String ARGS_HASH = "sha256:" + "a".repeat(64);

    private static ApprovalSnapshot snapshot(
            String instanceId,
            String taskId,
            ApprovalStatus status,
            UUID approver,
            Instant approvedAt,
            String argsHash) {
        return new ApprovalSnapshot(
                instanceId, taskId, status, REQUESTED_AT, approver, approvedAt, argsHash, 1L);
    }

    @Test
    @DisplayName("已批准但缺少批准人时拒绝")
    void approved_status_requires_approver() {
        assertThatThrownBy(
                        () ->
                                snapshot(
                                        "instance-1",
                                        "task-1",
                                        ApprovalStatus.APPROVED,
                                        null,
                                        REQUESTED_AT,
                                        ARGS_HASH))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("approver");
    }

    @Test
    @DisplayName("已批准但缺少批准时间时拒绝")
    void approved_status_requires_approved_at() {
        assertThatThrownBy(
                        () ->
                                snapshot(
                                        "instance-1",
                                        "task-1",
                                        ApprovalStatus.APPROVED,
                                        APPROVER,
                                        null,
                                        ARGS_HASH))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("approvedAt");
    }

    @ParameterizedTest
    @EnumSource(
            value = ApprovalStatus.class,
            names = {"PENDING", "REJECTED", "EXPIRED", "UNKNOWN"})
    @DisplayName("未通过的审批不需要批准人与批准时间")
    void non_approved_status_allows_missing_approver(ApprovalStatus status) {
        ApprovalSnapshot snapshot = snapshot("instance-1", "task-1", status, null, null, ARGS_HASH);

        assertThat(snapshot.status()).isEqualTo(status);
        assertThat(snapshot.approver()).isNull();
        assertThat(snapshot.approvedAt()).isNull();
    }

    @Test
    @DisplayName("审批状态必填")
    void status_is_mandatory() {
        assertThatThrownBy(() -> snapshot("instance-1", "task-1", null, null, null, ARGS_HASH))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status");
    }

    @ParameterizedTest
    @ValueSource(strings = {"instanceId", "taskId", "argsHash"})
    @DisplayName("必填文本为空白时按字段名拒绝")
    void blank_text_is_rejected(String component) {
        assertThatThrownBy(
                        () ->
                                snapshot(
                                        "instanceId".equals(component) ? " " : "instance-1",
                                        "taskId".equals(component) ? "" : "task-1",
                                        ApprovalStatus.PENDING,
                                        null,
                                        null,
                                        "argsHash".equals(component) ? " " : ARGS_HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(component);
    }

    @ParameterizedTest
    @ValueSource(strings = {"instanceId", "taskId", "argsHash"})
    @DisplayName("必填文本缺失时按字段名拒绝")
    void missing_text_is_rejected(String component) {
        assertThatThrownBy(
                        () ->
                                snapshot(
                                        "instanceId".equals(component) ? null : "instance-1",
                                        "taskId".equals(component) ? null : "task-1",
                                        ApprovalStatus.PENDING,
                                        null,
                                        null,
                                        "argsHash".equals(component) ? null : ARGS_HASH))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(component);
    }
}
