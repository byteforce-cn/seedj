package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.utils.HashesHelper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 账本记录哈希：前像字段、稳定性与篡改敏感性。 */
class LedgerHashTest {

    private static final UUID RECORD_ID = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID DECISION_ID = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");
    private static final UUID TENANT_ID = UUID.fromString("cccccccc-3333-3333-3333-333333333333");
    private static final Instant RECORDED_AT = Instant.parse("2026-11-18T00:00:00Z");

    @Test
    @DisplayName("前像是去掉 record_hash 的规范 JSON：逐字复算得到同一哈希")
    void hashCoversCanonicalJsonWithoutSelf() {
        DecisionRecord record = record(7L, Instant.parse("2026-11-18T01:02:03.456Z"), body());

        String preimage =
                "{\"body\":{\"kind\":\"execute_tool\",\"schema\":\"agent.decision.v1\","
                        + "\"step\":3},\"decision_id\":\""
                        + DECISION_ID
                        + "\",\"partition\":\"t_"
                        + TENANT_ID
                        + "\",\"prev_hash\":\""
                        + "1".repeat(64)
                        + "\",\"record_id\":\""
                        + RECORD_ID
                        + "\",\"record_kind\":\"intent\",\"recorded_at\":\"2026-11-18T01:02:03.456Z\","
                        + "\"sequence\":7,\"tenant_id\":\""
                        + TENANT_ID
                        + "\"}";

        assertThat(LedgerHash.of(record)).isEqualTo(HashesHelper.sha256Hex(preimage));
    }

    @Test
    @DisplayName("record_hash 入参被忽略：占位值不影响结果")
    void ignoresRecordHashOfInput() {
        DecisionRecord withGenesis = record(1L, RECORDED_AT, body());
        DecisionRecord withOther =
                new DecisionRecord(
                        RECORD_ID,
                        DECISION_ID,
                        DecisionRecordKind.INTENT,
                        DecisionRecord.partitionOf(TENANT_ID),
                        1L,
                        "1".repeat(64),
                        "f".repeat(64),
                        RECORDED_AT,
                        TENANT_ID,
                        body());

        assertThat(LedgerHash.of(withOther)).isEqualTo(LedgerHash.of(withGenesis));
    }

    @Test
    @DisplayName("序号、落账时间、记录体键序变化都会改变哈希（键序本身不敏感）")
    void hashIsSensitiveToCoveredFields() {
        DecisionRecord base = record(1L, RECORDED_AT, body());
        DecisionRecord otherSequence = record(2L, RECORDED_AT, body());
        DecisionRecord otherTime = record(1L, Instant.parse("2026-11-18T00:00:01Z"), body());
        DecisionRecord otherBody = record(1L, RECORDED_AT, Map.of("schema", "agent.decision.v1"));
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("step", 3);
        reordered.put("schema", "agent.decision.v1");
        reordered.put("kind", "execute_tool");
        DecisionRecord sameBodyReordered = record(1L, RECORDED_AT, reordered);

        assertThat(LedgerHash.of(otherSequence)).isNotEqualTo(LedgerHash.of(base));
        assertThat(LedgerHash.of(otherTime)).isNotEqualTo(LedgerHash.of(base));
        assertThat(LedgerHash.of(otherBody)).isNotEqualTo(LedgerHash.of(base));
        assertThat(LedgerHash.of(sameBodyReordered)).isEqualTo(LedgerHash.of(base));
    }

    @Test
    @DisplayName("记录体里的 null 值保留为 null 字面量，列表与嵌套对象参与规范化")
    void hashHandlesNullsListsAndNestedObjects() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "agent.decision.v1");
        body.put("action_class", null);
        body.put("targets", List.of("t-1", "t-2"));
        body.put("command", Map.of("type", "ticket.create", "expectedVersion", 7L));

        DecisionRecord record = record(1L, RECORDED_AT, body);

        assertThat(LedgerHash.of(record)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(LedgerHash.of(record(1L, RECORDED_AT, Map.of("schema", "agent.decision.v1"))))
                .isNotEqualTo(LedgerHash.of(record));
    }

    @Test
    @DisplayName("分区首条的 prev_hash 是 64 个零")
    void genesisIsSixtyFourZeros() {
        assertThat(LedgerHash.GENESIS).isEqualTo("0".repeat(64));
    }

    @Test
    @DisplayName("入参为 null 抛 NullPointerException")
    void rejectsNull() {
        assertThatThrownBy(() -> LedgerHash.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("unsigned");
    }

    private static DecisionRecord record(long sequence, Instant recordedAt, Map<String, Object> body) {
        return new DecisionRecord(
                RECORD_ID,
                DECISION_ID,
                DecisionRecordKind.INTENT,
                DecisionRecord.partitionOf(TENANT_ID),
                sequence,
                "1".repeat(64),
                LedgerHash.GENESIS,
                recordedAt,
                TENANT_ID,
                body);
    }

    private static Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "agent.decision.v1");
        body.put("kind", "execute_tool");
        body.put("step", 3);
        return body;
    }
}
