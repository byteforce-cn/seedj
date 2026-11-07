package cn.byteforce.seedj.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 审计链验证边界：空链、缺失哈希、事件 id 非法与摘要复算的跳过条件。 */
class AuditVerifierEdgeCaseTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    @Test
    @DisplayName("空链：null 与空列表都报 empty_chain(sequence=0)")
    void emptyChain() {
        List<AuditVerifier.AuditViolation> fromNull = AuditVerifier.verify(null);

        assertThat(fromNull)
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.getType()).isEqualTo("empty_chain");
                            assertThat(violation.getSequence()).isZero();
                            assertThat(violation.getMessage()).isEqualTo("审计链为空");
                            assertThat(violation.getExpected()).isNull();
                            assertThat(violation.getActual()).isNull();
                        });

        assertThat(AuditVerifier.verify(List.of(), Map.of()))
                .extracting(AuditVerifier.AuditViolation::getType)
                .containsExactly("empty_chain");
    }

    @Test
    @DisplayName("hash/prevHash 为 null 时按空串比较，报 invalid_hash 与 prev_hash_mismatch")
    void nullHashesAreReported() {
        AuditEntry<UUID> entry =
                new AuditEntry<>(
                        UUID.randomUUID(),
                        1,
                        java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        ACTOR,
                        "voucher.create",
                        "voucher",
                        null,
                        MAPPER.createObjectNode(),
                        null,
                        null,
                        null,
                        null);

        assertThat(AuditVerifier.verify(List.of(entry)))
                .extracting(AuditVerifier.AuditViolation::getType)
                .containsExactlyInAnyOrder("prev_hash_mismatch", "invalid_hash");
    }

    @Test
    @DisplayName("大小写不同的哈希按小写比较，不算篡改")
    void hashComparisonIsCaseInsensitive() {
        AuditEntry<UUID> unsigned =
                new AuditEntry<>(
                        UUID.randomUUID(),
                        1,
                        java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        ACTOR,
                        "voucher.create",
                        "voucher",
                        null,
                        MAPPER.createObjectNode(),
                        null,
                        null,
                        CanonicalHasher.GENESIS_PREV_HASH,
                        null);
        String hash = CanonicalHasher.computeHash(unsigned);
        AuditEntry<UUID> entry =
                new AuditEntry<>(
                        unsigned.getId(),
                        1,
                        unsigned.getTimestamp(),
                        ACTOR,
                        "voucher.create",
                        "voucher",
                        null,
                        unsigned.getPayload(),
                        null,
                        hash.toUpperCase(java.util.Locale.ROOT),
                        CanonicalHasher.GENESIS_PREV_HASH.toUpperCase(java.util.Locale.ROOT),
                        null);

        assertThat(AuditVerifier.verify(List.of(entry))).isEmpty();
    }

    @Test
    @DisplayName("event_ids 中的非法 UUID 报 payload_digest_missing_event")
    void invalidEventIdIsReported() {
        JsonNode payload = MAPPER.readTree("{\"event_ids\":[\"not-a-uuid\"]}");
        AuditEntry<UUID> entry = signedEntry(payload, AuditPayloadDigest.EMPTY_DIGEST);

        assertThat(AuditVerifier.verify(List.of(entry), Map.of()))
                .singleElement()
                .satisfies(
                        violation -> {
                            assertThat(violation.getType())
                                    .isEqualTo("payload_digest_missing_event");
                            assertThat(violation.getSequence()).isEqualTo(1);
                            assertThat(violation.getExpected()).isEqualTo("not-a-uuid");
                            assertThat(violation.getActual()).isNull();
                        });
    }

    @Test
    @DisplayName("payload 无 event_ids 或 event_ids 非数组时按空投影复算")
    void payloadWithoutEventIdsUsesEmptyProjection() {
        AuditEntry<UUID> withoutKey =
                signedEntry(MAPPER.createObjectNode(), AuditPayloadDigest.EMPTY_DIGEST);
        AuditEntry<UUID> nonArray =
                signedEntry(
                        MAPPER.readTree("{\"event_ids\":\"abc\"}"),
                        AuditPayloadDigest.EMPTY_DIGEST);

        assertThat(AuditVerifier.verify(List.of(withoutKey), Map.of())).isEmpty();
        assertThat(AuditVerifier.verify(List.of(nonArray), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("payload 为 null 时不复算摘要")
    void nullPayloadSkipsDigestRecompute() {
        AuditEntry<UUID> entry = signedEntry(null, AuditPayloadDigest.EMPTY_DIGEST);

        assertThat(AuditVerifier.verify(List.of(entry), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("digest 为空串或不提供事件导出时跳过复算")
    void blankDigestSkipsRecompute() {
        AuditEntry<UUID> noDigest = signedEntry(MAPPER.createObjectNode(), null);

        assertThat(AuditVerifier.verify(List.of(noDigest), Map.of())).isEmpty();
        assertThat(AuditVerifier.verify(List.of(noDigest))).isEmpty();
    }

    @Test
    @DisplayName("AuditViolation 暴露全部字段供工具输出")
    void violationAccessors() {
        AuditVerifier.AuditViolation violation =
                new AuditVerifier.AuditViolation(
                        "invalid_hash", 7, "哈希不符", "expected-hash", "actual-hash");

        assertThat(violation.getType()).isEqualTo("invalid_hash");
        assertThat(violation.getSequence()).isEqualTo(7);
        assertThat(violation.getMessage()).isEqualTo("哈希不符");
        assertThat(violation.getExpected()).isEqualTo("expected-hash");
        assertThat(violation.getActual()).isEqualTo("actual-hash");
    }

    private static AuditEntry<UUID> signedEntry(JsonNode payload, String digest) {
        AuditEntry<UUID> unsigned =
                new AuditEntry<>(
                        UUID.randomUUID(),
                        1,
                        java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        ACTOR,
                        "voucher.create",
                        "voucher",
                        null,
                        payload,
                        digest,
                        null,
                        CanonicalHasher.GENESIS_PREV_HASH,
                        null);
        return new AuditEntry<>(
                unsigned.getId(),
                1,
                unsigned.getTimestamp(),
                ACTOR,
                "voucher.create",
                "voucher",
                null,
                payload,
                digest,
                CanonicalHasher.computeHash(unsigned),
                CanonicalHasher.GENESIS_PREV_HASH,
                null);
    }
}
