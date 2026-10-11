package cn.byteforce.seedj.starter.eis.audit;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计条目（{@code 05-audit-contract/audit.ddl} 的 Java 视图）。
 *
 * <p>参与哈希的字段与 {@code hash-chain.spec.md §2} 的九元规范化一一对应：
 * id / sequence / timestamp / actor / action / resourceType / resourceId /
 * payload / prevHash；{@code signature} 不参与 v1 链验证（规范 §6：验证忽略）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisAuditEntry {

    private final UUID id;

    private final long sequence;

    private final Instant timestamp;

    private final UUID actor;

    private final String action;

    private final String resourceType;

    private final UUID resourceId;

    private final JsonNode payload;

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
    public EisAuditEntry(UUID id, long sequence, Instant timestamp, UUID actor, String action,
                         String resourceType, UUID resourceId, JsonNode payload, String hash, String prevHash,
                         String signature) {
        this.id = id;
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.actor = actor;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.payload = payload;
        this.hash = hash;
        this.prevHash = prevHash;
        this.signature = signature;
    }

    /**
     * 获取条目 ID。
     *
     * @return 条目 ID
     */
    public UUID getId() {
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
     * @return 操作人 UUID
     */
    public UUID getActor() {
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
    public UUID getResourceId() {
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
