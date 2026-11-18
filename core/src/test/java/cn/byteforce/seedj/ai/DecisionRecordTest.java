package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 账本记录的形状约束：分区内序号从 1 起，链上哈希只能是 64 位小写十六进制。
 *
 * <p>哈希格式在这里强制而不是在读链时强制：写进一条格式不对的哈希会让整条链无法复算， 事后无法判断是篡改还是写错了格式。
 */
class DecisionRecordTest {

    private static final UUID RECORD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DECISION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant RECORDED_AT = Instant.parse("2026-11-18T00:00:00Z");
    private static final String ZERO_HASH = "0".repeat(64);
    private static final String HASH = "a".repeat(64);

    private static DecisionRecord record(long sequence, String prevHash, String recordHash) {
        return new DecisionRecord(
                RECORD,
                DECISION,
                DecisionRecordKind.INTENT,
                DecisionRecord.partitionOf(TENANT),
                sequence,
                prevHash,
                recordHash,
                RECORDED_AT,
                TENANT,
                Map.of("kind", "read"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("分区序号小于 1 时拒绝")
    void sequence_below_one_is_rejected(long sequence) {
        assertThatThrownBy(() -> record(sequence, ZERO_HASH, HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "A1", "not-a-hash"})
    @DisplayName("本条哈希不是 64 位小写十六进制时按 hash 名拒绝")
    void record_hash_must_be_64_lowercase_hex(String hash) {
        assertThatThrownBy(() -> record(1L, ZERO_HASH, hash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordHash");
    }

    @Test
    @DisplayName("前一条哈希不是 64 位小写十六进制时被拒绝")
    void prev_hash_must_be_64_lowercase_hex() {
        assertThatThrownBy(() -> record(1L, "F".repeat(64), HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prevHash");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("分区名为空白时拒绝")
    void blank_partition_is_rejected(String partition) {
        assertThatThrownBy(
                        () ->
                                new DecisionRecord(
                                        RECORD,
                                        DECISION,
                                        DecisionRecordKind.INTENT,
                                        partition,
                                        1L,
                                        ZERO_HASH,
                                        HASH,
                                        RECORDED_AT,
                                        TENANT,
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition");
    }

    @Test
    @DisplayName("首条记录用全零哈希也能通过构造校验")
    void first_record_accepts_zero_prev_hash() {
        DecisionRecord record = record(1L, ZERO_HASH, HASH);

        assertThat(record.sequence()).isEqualTo(1L);
        assertThat(record.prevHash()).isEqualTo(ZERO_HASH);
        assertThat(record.partition()).isEqualTo("t_" + TENANT);
    }

    @Test
    @DisplayName("分区名由租户前缀派生，租户必填")
    void partition_of_prefixes_tenant() {
        assertThat(DecisionRecord.partitionOf(TENANT)).isEqualTo("t_" + TENANT);
        assertThatThrownBy(() -> DecisionRecord.partitionOf(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"recordId", "decisionId", "kind", "recordedAt", "tenantId", "body"})
    @DisplayName("必填组件缺失时按组件名拒绝")
    void mandatory_component_is_rejected(String component) {
        assertThatThrownBy(
                        () ->
                                new DecisionRecord(
                                        "recordId".equals(component) ? null : RECORD,
                                        "decisionId".equals(component) ? null : DECISION,
                                        "kind".equals(component) ? null : DecisionRecordKind.INTENT,
                                        DecisionRecord.partitionOf(TENANT),
                                        1L,
                                        ZERO_HASH,
                                        HASH,
                                        "recordedAt".equals(component) ? null : RECORDED_AT,
                                        "tenantId".equals(component) ? null : TENANT,
                                        "body".equals(component) ? null : Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(component);
    }
}
