package cn.byteforce.seedj.audit;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 审计条目（{@code 05-audit-contract/audit.ddl} 的 Java 视图）。
 *
 * <p>参与哈希的字段与 {@code hash-chain.spec.md §2} 的九元规范化一一对应：
 * id / sequence / timestamp / actor / action / resourceType / resourceId /
 * payload / prevHash；{@code signature} 不参与 v1 链验证（规范 §6：验证忽略）。</p>
 *
 * <p><b>标识类型</b>：{@code ID} 由适配器决定（契约使用 UUIDv7，其他场景可用
 * 字符串或业务值对象）。{@link CanonicalHasher} 只依赖 {@code ID.toString()} 的
 * 稳定文本表示；实现方必须保证同一值每次序列化结果一致。</p>
 *
 * @param <ID> 标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public final class AuditEntry<ID> {

    private final ID id;

    private final long sequence;

    private final Instant timestamp;

    private final ID actor;

    private final String action;

    private final String resourceType;

    private final ID resourceId;

    private final JsonNode payload;

    private final String payloadDigest;

    private final String hash;

    private final String prevHash;

    private final String signature;

    /**
     * 构造审计条目。
     *
     * @param id           条目 ID
     * @param sequence     链序号（从 1 起、连续）
     * @param timestamp    时间戳（UTC 微秒）
     * @param actor        操作人
     * @param action       动作
     * @param resourceType 资源类型
     * @param resourceId   资源 ID（可空）
     * @param payload      载荷（可空视为 {@code {}}）
     * @param hash         条目哈希（小写 hex；待计算时可为 {@code null}）
     * @param prevHash     前序哈希（小写 hex）
     * @param signature    可选签名（不参与验证）
     */
    public AuditEntry(ID id, long sequence, Instant timestamp, ID actor, String action,
                      String resourceType, ID resourceId, JsonNode payload, String hash, String prevHash,
                      String signature) {
        this(id, sequence, timestamp, actor, action, resourceType, resourceId, payload, null,
                hash, prevHash, signature);
    }

    /**
     * 构造带 v1.1 摘要的审计条目。
     *
     * @param id            条目 ID
     * @param sequence      链序号（从 1 起、连续）
     * @param timestamp     时间戳（UTC 微秒）
     * @param actor         操作人
     * @param action        动作
     * @param resourceType  资源类型
     * @param resourceId    资源 ID（可空）
     * @param payload       载荷（可空视为 {@code {}}）
     * @param payloadDigest 事件投影摘要（可空；非空必须是 64 位小写 hex）
     * @param hash          条目哈希（小写 hex；待计算时可为 {@code null}）
     * @param prevHash      前序哈希（小写 hex）
     * @param signature     可选签名（不参与验证）
     */
    public AuditEntry(ID id, long sequence, Instant timestamp, ID actor, String action,
                      String resourceType, ID resourceId, JsonNode payload, String payloadDigest,
                      String hash, String prevHash, String signature) {
        if (payloadDigest != null && !payloadDigest.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(
                    "payload digest must be 64 lowercase hex characters");
        }
        this.id = id;
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.actor = actor;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.payload = payload;
        this.payloadDigest = payloadDigest;
        this.hash = hash;
        this.prevHash = prevHash;
        this.signature = signature;
    }

    /**
     * 获取条目 ID。
     *
     * @return 条目 ID
     */
    public ID getId() {
        return id;
    }

    /**
     * 获取链序号。
     *
     * @return 序号
     */
    public long getSequence() {
        return sequence;
    }

    /**
     * 获取时间戳。
     *
     * @return UTC 时刻
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * 获取操作人。
     *
     * @return 操作人 ID
     */
    public ID getActor() {
        return actor;
    }

    /**
     * 获取动作。
     *
     * @return 动作
     */
    public String getAction() {
        return action;
    }

    /**
     * 获取资源类型。
     *
     * @return 资源类型
     */
    public String getResourceType() {
        return resourceType;
    }

    /**
     * 获取资源 ID。
     *
     * @return 资源 ID；可空
     */
    public ID getResourceId() {
        return resourceId;
    }

    /**
     * 获取载荷。
     *
     * @return 载荷 JSON
     */
    public JsonNode getPayload() {
        return payload;
    }

    /**
     * 获取事件投影摘要（v1.1）。
     *
     * @return 64 位小写 hex；未使用 v1.1 公式时为 {@code null}
     */
    public String getPayloadDigest() {
        return payloadDigest;
    }

    /**
     * 获取条目哈希。
     *
     * @return 小写 hex；未计算为 {@code null}
     */
    public String getHash() {
        return hash;
    }

    /**
     * 获取前序哈希。
     *
     * @return 小写 hex
     */
    public String getPrevHash() {
        return prevHash;
    }

    /**
     * 获取签名。
     *
     * @return 签名；可空
     */
    public String getSignature() {
        return signature;
    }
}
