package cn.byteforce.seedj.starter.eis.audit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CanonicalHasher} 冻结向量与规范化规则测试（{@code hash-chain.spec.md §2/§3}）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CanonicalHasherTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static EisAuditEntry genesisEntry() {
        return new EisAuditEntry(
                UUID.fromString("018f2e3a-1234-7000-8000-000000000001"),
                1L,
                Instant.parse("2026-10-08T09:00:00.000000+00:00"),
                UUID.fromString("018f2e3a-0000-7000-8000-000000000001"),
                "voucher.create",
                "voucher",
                UUID.fromString("018f2e3a-9999-7000-8000-000000000002"),
                MAPPER.readTree("{\"amount\": \"100.00\", \"currency\": \"CNY\"}"),
                null,
                CanonicalHasher.GENESIS_PREV_HASH,
                null);
    }

    @Test
    void frozenGenesisVectorCanonicalStringMatchesSpec() {
        String canonical = CanonicalHasher.canonicalString(genesisEntry());

        assertThat(canonical).isEqualTo(String.join("\n",
                "018f2e3a-1234-7000-8000-000000000001",
                "1",
                "2026-10-08T09:00:00.000000Z",
                "018f2e3a-0000-7000-8000-000000000001",
                "voucher.create",
                "voucher",
                "018f2e3a-9999-7000-8000-000000000002",
                "{\"amount\":\"100.00\",\"currency\":\"CNY\"}",
                "0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    void frozenGenesisVectorHashMatchesSpec() {
        assertThat(CanonicalHasher.computeHash(genesisEntry()))
                .isEqualTo("447c6e299d9adaec35d89e273b15d28752eeaac4674000b5df88d2e53a087410");
    }

    @Test
    void timestampIsNormalizedToUtcMicroseconds() {
        Instant instant = OffsetDateTime.parse("2026-10-08T17:00:00.123456789+08:00").toInstant();

        assertThat(CanonicalHasher.normalizeTimestamp(instant)).isEqualTo("2026-10-08T09:00:00.123456Z");
    }

    @Test
    void emptyAndMissingPayloadBecomeEmptyObject() {
        EisAuditEntry entry = new EisAuditEntry(UUID.randomUUID(), 2L, Instant.parse("2026-10-08T09:00:01Z"),
                UUID.randomUUID(), "a", "t", null, null, null, CanonicalHasher.GENESIS_PREV_HASH, null);

        String canonical = CanonicalHasher.canonicalString(entry);

        assertThat(canonical).endsWith("\n{}\n" + CanonicalHasher.GENESIS_PREV_HASH);
        assertThat(canonical).contains("\n\n"); // resource.id NULL → 空串
    }

    @Test
    void prevHashAndUuidsAreLowercased() {
        UUID id = UUID.fromString("018F2E3A-1234-7000-8000-0000000000AB");
        EisAuditEntry entry = new EisAuditEntry(id, 3L, Instant.parse("2026-10-08T09:00:02Z"),
                UUID.fromString("018F2E3A-0000-7000-8000-0000000000CD"), "a", "t", null, null, null,
                "AB".repeat(32), null);

        String canonical = CanonicalHasher.canonicalString(entry);

        assertThat(canonical).startsWith("018f2e3a-1234-7000-8000-0000000000ab");
        assertThat(canonical).contains("018f2e3a-0000-7000-8000-0000000000cd");
        assertThat(canonical).endsWith("ab".repeat(32));
    }
}
