package cn.byteforce.seedj.starter.eis.audit;

import cn.byteforce.seedj.starter.eis.core.CanonicalJson;
import cn.byteforce.seedj.starter.eis.core.EisHashes;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 审计链规范化与哈希：{@code 05-audit-contract/hash-chain.spec.md §2} 的逐字实现。
 *
 * <p>九元规范化（LF 连接、UTF-8、不转义非 ASCII）：</p>
 * <pre>
 * id(uuid 小写) / sequence(十进制) / timestamp(UTC 微秒, YYYY-MM-DDTHH:MM:SS.ffffffZ)
 * / actor(uuid 小写) / action / resource.type / resource.id(uuid 小写; NULL → 空串)
 * / payload(规范 JSON：键排序、无空白) / prev_hash(小写 hex)
 * </pre>
 *
 * <p><b>冻结向量</b>（规范 §3）：本实现必须对创世向量算出
 * {@code 447c6e299d9adaec35d89e273b15d28752eeaac4674000b5df88d2e53a087410}，
 * 测试逐位校验。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CanonicalHasher {

    /** 创世前序哈希：64 个 0。 */
    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private CanonicalHasher() {
    }

    /**
     * 计算条目的链哈希（小写 hex）。
     *
     * @param entry 审计条目
     * @return SHA-256 小写 hex
     */
    public static String computeHash(EisAuditEntry entry) {
        return EisHashes.sha256Hex(canonicalString(entry));
    }

    /**
     * 生成九元规范化字符串（LF 连接）。
     *
     * @param entry 审计条目
     * @return 规范化字符串
     */
    public static String canonicalString(EisAuditEntry entry) {
        return String.join("\n",
                normalizeUuid(entry.getId()),
                Long.toString(entry.getSequence()),
                normalizeTimestamp(entry.getTimestamp()),
                normalizeUuid(entry.getActor()),
                entry.getAction() == null ? "" : entry.getAction(),
                entry.getResourceType() == null ? "" : entry.getResourceType(),
                entry.getResourceId() == null ? "" : normalizeUuid(entry.getResourceId()),
                CanonicalJson.canonicalizePayload(payloadText(entry.getPayload())),
                entry.getPrevHash() == null ? "" : entry.getPrevHash().toLowerCase());
    }

    /**
     * 时间戳规范化：UTC、恰好 6 位小数、以 {@code Z} 结尾。
     *
     * @param instant 时刻
     * @return 规范时间戳字符串
     */
    public static String normalizeTimestamp(Instant instant) {
        return TIMESTAMP_FORMAT.format(instant);
    }

    /**
     * UUID 规范化：小写。
     *
     * @param value UUID
     * @return 小写字符串
     */
    public static String normalizeUuid(java.util.UUID value) {
        return value == null ? "" : value.toString().toLowerCase();
    }

    private static String payloadText(tools.jackson.databind.JsonNode payload) {
        return payload == null ? null : payload.toString();
    }
}
