package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 链校对结果的构造口径：通过时不带失败详情，失败时带第一条对不上的记录与两个哈希。
 *
 * <p>{@code checkedRecords == 0} 是合法输入——空链通过校对与“链错了”必须能区分开。
 */
class LedgerVerificationTest {

    private static final String PARTITION = "t_11111111-1111-1111-1111-111111111111";
    private static final UUID FAILED_RECORD =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("ok 不带失败详情")
    void ok_carries_no_failure_detail() {
        LedgerVerification verification = LedgerVerification.ok(PARTITION, 0L);

        assertThat(verification.ok()).isTrue();
        assertThat(verification.partition()).isEqualTo(PARTITION);
        assertThat(verification.checkedRecords()).isZero();
        assertThat(verification.failedRecordId()).isNull();
        assertThat(verification.expectedHash()).isNull();
        assertThat(verification.actualHash()).isNull();
    }

    @Test
    @DisplayName("mismatch 带第一条对不上的记录与两个哈希")
    void mismatch_carries_failure_detail() {
        LedgerVerification verification =
                LedgerVerification.mismatch(
                        PARTITION, 3L, FAILED_RECORD, "a".repeat(64), "b".repeat(64));

        assertThat(verification.ok()).isFalse();
        assertThat(verification.checkedRecords()).isEqualTo(3L);
        assertThat(verification.failedRecordId()).isEqualTo(FAILED_RECORD);
        assertThat(verification.expectedHash()).isEqualTo("a".repeat(64));
        assertThat(verification.actualHash()).isEqualTo("b".repeat(64));
    }

    @Test
    @DisplayName("分区名必填且不得空白")
    void partition_is_mandatory_and_not_blank() {
        assertThatThrownBy(() -> LedgerVerification.ok(null, 0L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("partition");
        assertThatThrownBy(() -> LedgerVerification.ok(" ", 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition");
    }

    @Test
    @DisplayName("已校对记录数不得为负")
    void checked_records_must_not_be_negative() {
        assertThatThrownBy(() -> LedgerVerification.ok(PARTITION, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkedRecords");
    }
}
