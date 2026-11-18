package cn.byteforce.seedj.starter.ai.plane;

import cn.byteforce.seedj.ai.DecisionRecord;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 从已落账的 {@code intent} 记录体里读回派生 outcome 需要的那两项。
 *
 * <p>上报与对账都不重新解释请求：命令记录的查找键 {@code issuer} 取 intent 里写下的
 * {@code identity.actor}（不是当前配置里的 actor，也不是上报方自称的主体），指纹比对取 intent 里写下的
 * {@code idem_fingerprint}。这样即使配置里的身份后来变了，「这条意图对应哪条命令」的答案仍然不变。
 *
 * @param actor 意图里的发起主体（命令记录的 {@code issuer}）
 * @param idemFingerprint 意图的幂等指纹（与命令记录的 {@code payload_fingerprint} 同口径）
 * @author ByteForce
 * @since 1.0.0
 */
record RecordedIntent(UUID actor, String idemFingerprint) {

    /**
     * 读回一条 {@code intent} 记录的查找键与幂等指纹。
     *
     * @param record 账本记录
     * @return 意图里的主体与幂等指纹
     * @throws NullPointerException {@code record} 为 {@code null}
     * @throws IllegalStateException 记录体缺 {@code identity.actor} 或 {@code idem_fingerprint}
     */
    static RecordedIntent of(DecisionRecord record) {
        Objects.requireNonNull(record, "record");
        Object identity = record.body().get("identity");
        if (!(identity instanceof Map<?, ?> fields)
                || !(fields.get("actor") instanceof String actor)) {
            throw new IllegalStateException("intent 记录缺少 identity.actor: " + record.recordId());
        }
        Object fingerprint = record.body().get("idem_fingerprint");
        if (!(fingerprint instanceof String value)) {
            throw new IllegalStateException(
                    "intent 记录缺少 idem_fingerprint: " + record.recordId());
        }
        return new RecordedIntent(UUID.fromString(actor), value);
    }
}
