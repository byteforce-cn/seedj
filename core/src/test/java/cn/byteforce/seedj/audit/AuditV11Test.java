package cn.byteforce.seedj.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计 v1.1：冻结向量、事件投影摘要与固定夹具回放。
 */
class AuditV11Test {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");
    private static final UUID RESOURCE = UUID.fromString("018f2e3a-9999-7000-8000-000000000002");
    private static final UUID EVENT_ID = UUID.fromString("018f2e3a-aaaa-7000-8000-000000000001");
    private static final String EMPTY_DIGEST =
            "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945";

    @Test
    @DisplayName("v1.0 创世向量保持逐字节不变")
    void v10FrozenGenesisVectorStaysUnchanged() {
        assertThat(CanonicalHasher.computeHash(genesisEntry(null)))
                .isEqualTo("447c6e299d9adaec35d89e273b15d28752eeaac4674000b5df88d2e53a087410");
    }

    @Test
    @DisplayName("v1.1 摘要向量与规范一致")
    void v11FrozenDigestVectorMatchesSpec() {
        AuditEvent event = new AuditEvent(EVENT_ID, "voucher.created",
                MAPPER.readTree("{\"amount\": \"100.00\", \"currency\": \"CNY\"}"));

        assertThat(AuditPayloadDigest.compute(List.of(event)))
                .isEqualTo("b3a88a20915cc5a9a3c4a5c05e90b32be38b4e3ecafdc5f94d0fe878fb402913");
    }

    @Test
    @DisplayName("v1.1 条目哈希在第 11 行追加 digest")
    void v11EntryHashMatchesSpec() {
        assertThat(CanonicalHasher.canonicalString(genesisEntry(
                "b3a88a20915cc5a9a3c4a5c05e90b32be38b4e3ecafdc5f94d0fe878fb402913"))
                .split("\n", -1)).hasSize(10);
        assertThat(CanonicalHasher.computeHash(genesisEntry(
                "b3a88a20915cc5a9a3c4a5c05e90b32be38b4e3ecafdc5f94d0fe878fb402913")))
                .isEqualTo("fb4cc4620dba2250bca59861f177e5406e19b35136582386d0e15fb96bb98969");
    }

    @Test
    @DisplayName("空事件投影使用冻结常量")
    void emptyProjectionUsesFrozenConstant() {
        assertThat(AuditPayloadDigest.compute(List.of())).isEqualTo(EMPTY_DIGEST);
        assertThat(AuditPayloadDigest.EMPTY_DIGEST).isEqualTo(EMPTY_DIGEST);
    }

    @Test
    @DisplayName("合法链与含 digest 的固定夹具均通过")
    void fixedValidFixturesPass() {
        assertThat(AuditVerifier.verify(loadChain("valid.chain.json"))).isEmpty();
        assertThat(AuditVerifier.verify(loadChain("valid-digest.chain.json"),
                loadEvents("digest-events.json"))).isEmpty();
    }

    @Test
    @DisplayName("没有事件导出时跳过 digest 复算，v1.0 兼容")
    void verifierSkipsDigestWhenNoEventExportGiven() {
        assertThat(AuditVerifier.verify(loadChain("valid-digest.chain.json"))).isEmpty();
    }

    @Test
    @DisplayName("篡改、缺口与断链夹具给出各自错误类型")
    void fixedInvalidFixturesReportExpectedTypes() {
        assertThat(types(loadChain("tampered.chain.json"))).contains("invalid_hash");
        assertThat(types(loadChain("gap.chain.json"))).contains("sequence_gap");
        assertThat(types(loadChain("broken.chain.json"))).contains("prev_hash_mismatch");
    }

    @Test
    @DisplayName("事件正文篡改报 payload_digest_mismatch")
    void verifierDetectsTamperedEventPayload() {
        assertThat(types(loadChain("valid-digest.chain.json"),
                loadEvents("tampered-events.json")))
                .contains("payload_digest_mismatch");
    }

    @Test
    @DisplayName("事件导出缺少被引用事件报 payload_digest_missing_event")
    void verifierReportsMissingReferencedEvent() {
        Map<UUID, AuditEvent> events = new LinkedHashMap<>(loadEvents("digest-events.json"));
        events.remove(UUID.fromString("018f2e3a-4000-8000-0000-0000000000e2"));

        assertThat(types(loadChain("valid-digest.chain.json"), events))
                .contains("payload_digest_missing_event");
    }

    @Test
    @DisplayName("摘要按 event_ids 顺序复算，与导出映射的迭代顺序无关")
    void eventOrderFollowsEventIdsNotExportOrder() {
        Map<UUID, AuditEvent> source = loadEvents("digest-events.json");
        Map<UUID, AuditEvent> reversed = new LinkedHashMap<>();
        List<UUID> ids = new ArrayList<>(source.keySet());
        for (int i = ids.size() - 1; i >= 0; i--) {
            reversed.put(ids.get(i), source.get(ids.get(i)));
        }

        assertThat(AuditVerifier.verify(loadChain("valid-digest.chain.json"), reversed))
                .isEmpty();
    }

    private static AuditEntry<UUID> genesisEntry(String payloadDigest) {
        return new AuditEntry<>(ID, 1L, Instant.parse("2026-10-08T09:00:00Z"), ACTOR,
                "voucher.create", "voucher", RESOURCE,
                MAPPER.readTree("{\"amount\": \"100.00\", \"currency\": \"CNY\"}"),
                payloadDigest, null, CanonicalHasher.GENESIS_PREV_HASH, null);
    }

    private static List<AuditEntry<UUID>> loadChain(String name) {
        JsonNode root = read(name);
        List<AuditEntry<UUID>> entries = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode resource = node.get("resource");
            JsonNode resourceId = resource.get("id");
            entries.add(new AuditEntry<>(
                    UUID.fromString(node.get("id").asString()),
                    node.get("sequence").asLong(),
                    OffsetDateTime.parse(node.get("timestamp").asString()).toInstant(),
                    UUID.fromString(node.get("actor").asString()),
                    node.get("action").asString(),
                    resource.get("type").asString(),
                    resourceId == null ? null : UUID.fromString(resourceId.asString()),
                    node.get("payload"),
                    node.get("payload_digest") == null
                            ? null : node.get("payload_digest").asString(),
                    node.get("hash") == null ? null : node.get("hash").asString(),
                    node.get("prev_hash").asString(),
                    null));
        }
        return entries;
    }

    private static Map<UUID, AuditEvent> loadEvents(String name) {
        Map<UUID, AuditEvent> events = new LinkedHashMap<>();
        for (JsonNode node : read(name).get("events")) {
            AuditEvent event = new AuditEvent(UUID.fromString(node.get("id").asString()),
                    node.get("type").asString(), node.get("payload"));
            events.put(event.id(), event);
        }
        return events;
    }

    private static List<String> types(List<AuditEntry<UUID>> entries) {
        return types(entries, null);
    }

    private static List<String> types(List<AuditEntry<UUID>> entries,
                                      Map<UUID, AuditEvent> events) {
        return AuditVerifier.verify(entries, events).stream()
                .map(AuditVerifier.AuditViolation::getType)
                .toList();
    }

    private static JsonNode read(String name) {
        try (InputStream stream = AuditV11Test.class.getResourceAsStream("/audit/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture: " + name);
            }
            return MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
