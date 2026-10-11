package cn.byteforce.seedj.starter.eis.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 审计链验证器：四类错误的检出语义与 {@code verify.tool} 对齐
 * （{@code sequence_gap} / {@code prev_hash_mismatch} / {@code invalid_hash} / {@code empty_chain}）。
 *
 * <p>验证算法（规范 §4）：逐条推进期望序号与期望前序哈希，重算每条哈希。
 * 返回错误列表；空列表 = 验证通过。与参考实现一致的行为：断链/改序后
 * 仍以"已存哈希或重算哈希"续链推进，尽量多报错误。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisAuditVerifier {

    private EisAuditVerifier() {
    }

    /**
     * 验证审计链。
     *
     * @param entries 审计条目（按 sequence 升序；空链返回 empty_chain）
     * @return 错误列表（空 = 通过）
     */
    public static List<EisAuditViolation> verify(List<EisAuditEntry> entries) {
        List<EisAuditViolation> errors = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            errors.add(new EisAuditViolation("empty_chain", 0, "审计链为空", null, null));
            return errors;
        }
        long expectedSequence = 1;
        String prevHash = CanonicalHasher.GENESIS_PREV_HASH;
        for (EisAuditEntry entry : entries) {
            long sequence = entry.getSequence();
            if (sequence != expectedSequence) {
                errors.add(new EisAuditViolation("sequence_gap", sequence,
                        "期望 sequence=" + expectedSequence + "，实际=" + sequence, null, null));
            }
            String storedPrev = entry.getPrevHash() == null ? "" : entry.getPrevHash().toLowerCase();
            if (!storedPrev.equals(prevHash)) {
                errors.add(new EisAuditViolation("prev_hash_mismatch", sequence,
                        "prev_hash 与上一条记录哈希不衔接", prevHash, storedPrev));
            }
            String storedHash = entry.getHash() == null ? "" : entry.getHash().toLowerCase();
            String computed = CanonicalHasher.computeHash(entry);
            if (!storedHash.equals(computed)) {
                errors.add(new EisAuditViolation("invalid_hash", sequence,
                        "记录内容与哈希不符（可能被篡改）", computed, storedHash));
            }
            prevHash = storedHash.isEmpty() ? computed : storedHash;
            expectedSequence = sequence + 1;
        }
        return errors;
    }

    /**
     * 审计链违规项。
     */
    public static final class EisAuditViolation {

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
        public EisAuditViolation(String type, long sequence, String message, String expected, String actual) {
            this.type = type;
            this.sequence = sequence;
            this.message = message;
            this.expected = expected;
            this.actual = actual;
        }

        /**
         * 获取错误类型。
         *
         * @return sequence_gap / prev_hash_mismatch / invalid_hash / empty_chain
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
