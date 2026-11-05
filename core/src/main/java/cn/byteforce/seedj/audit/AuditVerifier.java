package cn.byteforce.seedj.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 审计链验证器：错误语义与 {@code verify.tool} 对齐
 * （{@code sequence_gap} / {@code prev_hash_mismatch} / {@code invalid_hash} /
 * {@code empty_chain}，以及提供事件导出时的 {@code payload_digest_mismatch} /
 * {@code payload_digest_missing_event}）。
 *
 * <p>验证算法（规范 §4）：逐条推进期望序号与期望前序哈希，重算每条哈希。
 * 返回错误列表；空列表 = 验证通过。与参考实现一致的行为：断链/改序后
 * 仍以“已存哈希或重算哈希”续链推进，尽量多报错误。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class AuditVerifier {

    private AuditVerifier() {
    }

    /**
     * 验证审计链（不提供事件导出：v1.1 条目跳过摘要复算）。
     *
     * @param entries 审计条目（按 sequence 升序；空链返回 empty_chain）
     * @param <ID>    标识类型
     * @return 错误列表（空 = 通过）
     */
    public static <ID> List<AuditViolation> verify(List<AuditEntry<ID>> entries) {
        return verify(entries, null);
    }

    /**
     * 验证审计链，并可选地对 v1.1 条目复算 {@code payload_digest}。
     *
     * @param entries 审计条目（按 sequence 升序；空链返回 empty_chain）
     * @param events  事件导出，按事件 ID 索引；{@code null} 表示不复算摘要
     * @param <ID>    标识类型
     * @return 错误列表（空 = 通过）
     */
    public static <ID> List<AuditViolation> verify(List<AuditEntry<ID>> entries,
                                                   Map<UUID, AuditEvent> events) {
        List<AuditViolation> errors = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            errors.add(new AuditViolation("empty_chain", 0, "审计链为空", null, null));
            return errors;
        }
        long expectedSequence = 1;
        String prevHash = CanonicalHasher.GENESIS_PREV_HASH;
        for (AuditEntry<ID> entry : entries) {
            long sequence = entry.getSequence();
            if (sequence != expectedSequence) {
                errors.add(new AuditViolation("sequence_gap", sequence,
                        "期望 sequence=" + expectedSequence + "，实际=" + sequence, null, null));
            }
            String storedPrev = entry.getPrevHash() == null ? ""
                    : entry.getPrevHash().toLowerCase(Locale.ROOT);
            if (!storedPrev.equals(prevHash)) {
                errors.add(new AuditViolation("prev_hash_mismatch", sequence,
                        "prev_hash 与上一条记录哈希不衔接", prevHash, storedPrev));
            }
            String storedHash = entry.getHash() == null ? ""
                    : entry.getHash().toLowerCase(Locale.ROOT);
            String computed = CanonicalHasher.computeHash(entry);
            if (!storedHash.equals(computed)) {
                errors.add(new AuditViolation("invalid_hash", sequence,
                        "记录内容与哈希不符（可能被篡改）", computed, storedHash));
            }
            verifyPayloadDigest(entry, events, errors);
            prevHash = storedHash.isEmpty() ? computed : storedHash;
            expectedSequence = sequence + 1;
        }
        return errors;
    }

    private static void verifyPayloadDigest(AuditEntry<?> entry, Map<UUID, AuditEvent> events,
                                            List<AuditViolation> errors) {
        String digest = entry.getPayloadDigest();
        if (digest == null || digest.isBlank() || events == null) {
            return;
        }
        long sequence = entry.getSequence();
        List<AuditEvent> projection = new ArrayList<>();
        for (String eventId : eventIds(entry)) {
            UUID id;
            try {
                id = UUID.fromString(eventId);
            } catch (IllegalArgumentException error) {
                errors.add(new AuditViolation("payload_digest_missing_event", sequence,
                        "事件导出中缺少被引用事件", eventId, null));
                return;
            }
            AuditEvent event = events.get(id);
            if (event == null) {
                errors.add(new AuditViolation("payload_digest_missing_event", sequence,
                        "事件导出中缺少被引用事件", eventId, null));
                return;
            }
            projection.add(event);
        }
        String computed = AuditPayloadDigest.compute(projection);
        if (!computed.equals(digest.toLowerCase(Locale.ROOT))) {
            errors.add(new AuditViolation("payload_digest_mismatch", sequence,
                    "事件正文复算摘要与条目不符（可能被篡改）", computed, digest));
        }
    }

    private static List<String> eventIds(AuditEntry<?> entry) {
        var payload = entry.getPayload();
        if (payload == null) {
            return List.of();
        }
        var ids = payload.get("event_ids");
        if (ids == null || !ids.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(ids.size());
        for (var node : ids) {
            result.add(node.asString());
        }
        return result;
    }

    /**
     * 审计链违规项。
     */
    public static final class AuditViolation {

        private final String type;

        private final long sequence;

        private final String message;

        private final String expected;

        private final String actual;

        /**
         * 构造违规项。
         *
         * @param type     错误类型
         * @param sequence 涉及序号
         * @param message  描述
         * @param expected 期望值（可空）
         * @param actual   实际值（可空）
         */
        public AuditViolation(String type, long sequence, String message, String expected, String actual) {
            this.type = type;
            this.sequence = sequence;
            this.message = message;
            this.expected = expected;
            this.actual = actual;
        }

        /**
         * 获取错误类型。
         *
         * @return sequence_gap / prev_hash_mismatch / invalid_hash / empty_chain /
         *         payload_digest_mismatch / payload_digest_missing_event
         */
        public String getType() {
            return type;
        }

        /**
         * 获取涉及序号。
         *
         * @return 序号（empty_chain 为 0）
         */
        public long getSequence() {
            return sequence;
        }

        /**
         * 获取描述。
         *
         * @return 描述
         */
        public String getMessage() {
            return message;
        }

        /**
         * 获取期望值。
         *
         * @return 期望值；可空
         */
        public String getExpected() {
            return expected;
        }

        /**
         * 获取实际值。
         *
         * @return 实际值；可空
         */
        public String getActual() {
            return actual;
        }
    }
}
