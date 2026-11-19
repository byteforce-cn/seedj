package cn.byteforce.seedj.starter.ai.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** intent 记录体读回的 fail-closed：缺字段就报错，不猜默认值。 */
class RecordedIntentTest {

    @Test
    @DisplayName("缺 identity 直接失败")
    void missingIdentityFails() {
        DecisionRecord record =
                new DecisionRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        DecisionRecordKind.INTENT,
                        "t_x",
                        1,
                        "0".repeat(64),
                        "1".repeat(64),
                        Instant.now(),
                        UUID.randomUUID(),
                        Map.of("schema", "agent.decision.v1", "record_kind", "intent"));

        assertThatThrownBy(() -> RecordedIntent.of(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity");
    }

    @Test
    @DisplayName("缺 command 直接失败")
    void missingCommandFails() {
        DecisionRecord record =
                new DecisionRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        DecisionRecordKind.INTENT,
                        "t_x",
                        1,
                        "0".repeat(64),
                        "1".repeat(64),
                        Instant.now(),
                        UUID.randomUUID(),
                        Map.of(
                                "schema", "agent.decision.v1",
                                "record_kind", "intent",
                                "identity",
                                        Map.of(
                                                "actor", UUID.randomUUID().toString(),
                                                "on_behalf_of", UUID.randomUUID().toString()),
                                "args_hash", "sha256:a",
                                "idem_fingerprint", "f".repeat(64),
                                "rationale", "reason"));

        assertThatThrownBy(() -> RecordedIntent.of(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command");
    }
}
