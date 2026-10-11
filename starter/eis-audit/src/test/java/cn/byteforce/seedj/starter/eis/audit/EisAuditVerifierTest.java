package cn.byteforce.seedj.starter.eis.audit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EisAuditVerifier} 四类错误检出行为测试（镜像 {@code verify.tool --self-test}）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuditVerifierTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * 构造合法链（逐条计算哈希）。
     */
    private static List<EisAuditEntry> buildChain(int size) {
        List<EisAuditEntry> chain = new ArrayList<>();
        String prev = CanonicalHasher.GENESIS_PREV_HASH;
        for (int seq = 1; seq <= size; seq++) {
            EisAuditEntry draft = new EisAuditEntry(
                    UUID.fromString(String.format("018f2e3a-1234-7000-8000-00000000%04d", seq)),
                    seq,
                    Instant.parse("2026-10-08T09:00:00.000000Z").plusSeconds(seq),
                    UUID.fromString("018f2e3a-0000-7000-8000-000000000001"),
                    "voucher.create",
                    "voucher",
                    UUID.fromString("018f2e3a-9999-7000-8000-000000000002"),
                    MAPPER.readTree("{\"amount\":\"100.00\",\"currency\":\"CNY\"}"),
                    null, prev, null);
            String hash = CanonicalHasher.computeHash(draft);
            chain.add(new EisAuditEntry(draft.getId(), draft.getSequence(), draft.getTimestamp(), draft.getActor(),
                    draft.getAction(), draft.getResourceType(), draft.getResourceId(), draft.getPayload(), hash,
                    prev, null));
            prev = hash;
        }
        return chain;
    }

    @Test
    void validChainPasses() {
        assertThat(EisAuditVerifier.verify(buildChain(3))).isEmpty();
    }

    @Test
    void emptyChainIsReported() {
        List<EisAuditVerifier.EisAuditViolation> errors = EisAuditVerifier.verify(List.of());

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getType()).isEqualTo("empty_chain");
        assertThat(EisAuditVerifier.verify(null)).hasSize(1);
    }

    @Test
    void tamperedPayloadIsDetectedAsInvalidHash() {
        List<EisAuditEntry> chain = buildChain(3);
        EisAuditEntry second = chain.get(1);
        chain.set(1, new EisAuditEntry(second.getId(), second.getSequence(), second.getTimestamp(),
                second.getActor(), second.getAction(), second.getResourceType(), second.getResourceId(),
                MAPPER.readTree("{\"amount\":\"999.00\",\"currency\":\"CNY\"}"), second.getHash(),
                second.getPrevHash(), null));

        List<EisAuditVerifier.EisAuditViolation> errors = EisAuditVerifier.verify(chain);

        assertThat(errors).anyMatch(error -> "invalid_hash".equals(error.getType())
                && error.getSequence() == 2L);
    }

    @Test
    void sequenceGapIsDetected() {
        List<EisAuditEntry> chain = buildChain(3);
        List<EisAuditEntry> gapped = List.of(chain.get(0), chain.get(2));

        List<EisAuditVerifier.EisAuditViolation> errors = EisAuditVerifier.verify(gapped);

        assertThat(errors).anyMatch(error -> "sequence_gap".equals(error.getType()));
        assertThat(errors).anyMatch(error -> "prev_hash_mismatch".equals(error.getType()));
    }

    @Test
    void brokenPrevHashIsDetectedEvenWhenRehashed() {
        List<EisAuditEntry> chain = buildChain(3);
        EisAuditEntry third = chain.get(2);
        EisAuditEntry tampered = new EisAuditEntry(third.getId(), third.getSequence(), third.getTimestamp(),
                third.getActor(), third.getAction(), third.getResourceType(), third.getResourceId(),
                third.getPayload(), null, "f".repeat(64), null);
        EisAuditEntry rehashed = new EisAuditEntry(tampered.getId(), tampered.getSequence(),
                tampered.getTimestamp(), tampered.getActor(), tampered.getAction(), tampered.getResourceType(),
                tampered.getResourceId(), tampered.getPayload(), CanonicalHasher.computeHash(tampered),
                tampered.getPrevHash(), null);
        chain.set(2, rehashed);

        List<EisAuditVerifier.EisAuditViolation> errors = EisAuditVerifier.verify(chain);

        assertThat(errors).anyMatch(error -> "prev_hash_mismatch".equals(error.getType())
                && error.getSequence() == 3L);
        assertThat(errors).noneMatch(error -> "invalid_hash".equals(error.getType()));
    }

    @Test
    void genesisSequenceMustStartAtOne() {
        List<EisAuditEntry> chain = buildChain(2);
        List<EisAuditEntry> shifted = List.of(chain.get(0));
        EisAuditEntry first = shifted.get(0);
        List<EisAuditEntry> wrongStart = List.of(new EisAuditEntry(first.getId(), 5L, first.getTimestamp(),
                first.getActor(), first.getAction(), first.getResourceType(), first.getResourceId(),
                first.getPayload(), first.getHash(), first.getPrevHash(), null));

        assertThat(EisAuditVerifier.verify(wrongStart))
                .anyMatch(error -> "sequence_gap".equals(error.getType()));
    }
}
