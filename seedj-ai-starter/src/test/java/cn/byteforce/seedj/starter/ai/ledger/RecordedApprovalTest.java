package cn.byteforce.seedj.starter.ai.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordBodies;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** approval 记录体的读回：字段名与 {@link DecisionRecordBodies} 完全对齐。 */
class RecordedApprovalTest {

    @Test
    @DisplayName("APPROVED 快照可逐字读回")
    void roundTripsApprovalBody() {
        ApprovalSnapshot snapshot =
                new ApprovalSnapshot(
                        "i-1",
                        "t-1",
                        ApprovalStatus.APPROVED,
                        Instant.parse("2026-11-19T00:00:00Z"),
                        UUID.fromString("018f2e3a-0000-7000-8000-000000000118"),
                        Instant.parse("2026-11-19T01:00:00Z"),
                        "sha256:abc",
                        3L);
        DecisionRecord record =
                new DecisionRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        DecisionRecordKind.APPROVAL,
                        "t_x",
                        1,
                        "0".repeat(64),
                        "1".repeat(64),
                        Instant.now(),
                        UUID.randomUUID(),
                        DecisionRecordBodies.approval(snapshot));

        assertThat(RecordedApproval.of(record)).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("PENDING 快照没有批准人/批准时间也可读回")
    void pendingSnapshotRoundTrips() {
        ApprovalSnapshot snapshot =
                new ApprovalSnapshot(
                        "i-2",
                        "t-2",
                        ApprovalStatus.PENDING,
                        Instant.parse("2026-11-19T00:00:00Z"),
                        null,
                        null,
                        "sha256:def",
                        null);
        DecisionRecord record =
                new DecisionRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        DecisionRecordKind.APPROVAL,
                        "t_x",
                        2,
                        "0".repeat(64),
                        "1".repeat(64),
                        Instant.now(),
                        UUID.randomUUID(),
                        DecisionRecordBodies.approval(snapshot));

        assertThat(RecordedApproval.of(record)).isEqualTo(snapshot);
    }
}
