package cn.byteforce.seedj.audit;

import cn.byteforce.seedj.core.utils.CanonicalJsonHelper;
import cn.byteforce.seedj.core.utils.HashesHelper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 审计链规范化与哈希：{@code 05-audit-contract/hash-chain.spec.md §2} 的逐字实现。
 *
 * <p>九元规范化（LF 连接、UTF-8、不转义非 ASCII）：</p>
 * <pre>
 * id / sequence(十进制) / timestamp(UTC 微秒, YYYY-MM-DDTHH:MM:SS.ffffffZ)
 * / actor / action / resource.type / resource.id(NULL → 空串)
 * / payload(规范 JSON：键排序、无空白) / prev_hash(小写 hex)
 * </pre>
 *
 * <p><b>标识文本</b>：{@code UUID} 使用标准小写形式；其他 {@code ID} 类型使用
 * {@link Object#toString()}。实现方必须保证同一标识每次得到相同文本。</p>
 *
 * <p><b>冻结向量</b>（规范 §3）：本实现必须对创世向量算出
 * {@code 447c6e299d9adaec35d89e273b15d28752eeaac4674000b5df88d2e53a087410}。</p>
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
    public static String computeHash(AuditEntry<?> entry) {
        return HashesHelper.sha256Hex(canonicalString(entry));
    }

    /**
     * 生成九元规范化字符串（LF 连接）。
     *
     * @param entry 审计条目
     * @return 规范化字符串
     */
    public static String canonicalString(AuditEntry<?> entry) {
        Objects.requireNonNull(entry, "entry");
        return String.join("\n",
                normalizeId(entry.getId()),
                Long.toString(entry.getSequence()),
                normalizeTimestamp(entry.getTimestamp()),
                normalizeId(entry.getActor()),
                nullToEmpty(entry.getAction()),
                nullToEmpty(entry.getResourceType()),
                normalizeId(entry.getResourceId()),
                CanonicalJsonHelper.canonicalizePayload(payloadText(entry.getPayload())),
                lower(nullToEmpty(entry.getPrevHash())));
    }

    /**
     * 时间戳规范化：UTC、恰好 6 位小数、以 {@code Z} 结尾。
     *
     * @param instant 时刻
     * @return 规范时间戳字符串
     */
    public static String normalizeTimestamp(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return TIMESTAMP_FORMAT.format(instant);
    }

    /**
     * 标识规范化：{@code UUID} 转标准小写；其余类型取 {@code toString()}。
     *
     * @param value 标识；可空
     * @return 规范文本；{@code null} → 空串
     */
    public static String normalizeId(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof UUID uuid) {
            return uuid.toString().toLowerCase(Locale.ROOT);
        }
        return value.toString();
    }

    private static String payloadText(tools.jackson.databind.JsonNode payload) {
        return payload == null ? null : payload.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
