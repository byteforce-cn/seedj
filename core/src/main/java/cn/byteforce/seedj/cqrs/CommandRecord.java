package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一条持久化的命令记录（命令契约的元结构）。
 *
 * <p>{@code payloadFingerprint} 是脱敏前载荷的 SHA-256，只存摘要；命令记录里的
 * {@code payload} 允许为空对象。终态字段与状态强绑定：{@code PENDING} 不得带
 * {@code locator/version} 或拒绝信息，{@code ACCEPTED} 必须有
 * {@code locator/version}，{@code REJECTED} 必须有拒绝码与原因。</p>
 *
 * @param id                 命令 ID（UUIDv7）
 * @param type               命令类型
 * @param issuedAt           签发时间
 * @param issuer             发起者
 * @param payload            载荷（可脱敏；允许含 {@code null} 值）
 * @param idempotencyKey     幂等键
 * @param payloadFingerprint 脱敏前载荷的 SHA-256 小写十六进制
 * @param status             状态
 * @param rejectionCode      拒绝码（仅拒绝态）
 * @param rejectionReason    拒绝原因（仅拒绝态）
 * @param eventIds           本次命令产生的事件 ID
 * @param locator            写入的聚合根 ID（仅接受态，与 {@code targetRef} 二选一）
 * @param version            目标对象版本（仅接受态）
 * @param targetRef          事件溯源命令的聚合标识文本（仅接受态，与 {@code locator} 二选一）
 * @param createdAt          记录创建时间
 * @param leaseUntil         PENDING 租约到期时间；终态保留原值
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandRecord(
        UUID id,
        String type,
        Instant issuedAt,
        UUID issuer,
        Map<String, Object> payload,
        String idempotencyKey,
        String payloadFingerprint,
        CommandStatus status,
        String rejectionCode,
        String rejectionReason,
        List<UUID> eventIds,
        UUID locator,
        Long version,
        String targetRef,
        Instant createdAt,
        Instant leaseUntil) {

    private static final Pattern TYPE = Pattern.compile("^[a-z][a-z0-9_.]*$");

    private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");

    /**
     * 兼容既有装配：不使用事件溯源标识文本。
     *
     * @param id                 命令 ID
     * @param type               命令类型
     * @param issuedAt           签发时间
     * @param issuer             发起者
     * @param payload            载荷
     * @param idempotencyKey     幂等键
     * @param payloadFingerprint 载荷指纹
     * @param status             状态
     * @param rejectionCode      拒绝码
     * @param rejectionReason    拒绝原因
     * @param eventIds           事件 ID
     * @param locator            聚合根 ID
     * @param version            对象版本
     * @param createdAt          记录创建时间
     * @param leaseUntil         租约到期时间
     */
    public CommandRecord(UUID id, String type, Instant issuedAt, UUID issuer,
                         Map<String, Object> payload, String idempotencyKey,
                         String payloadFingerprint, CommandStatus status, String rejectionCode,
                         String rejectionReason, List<UUID> eventIds, UUID locator, Long version,
                         Instant createdAt, Instant leaseUntil) {
        this(id, type, issuedAt, issuer, payload, idempotencyKey, payloadFingerprint, status,
                rejectionCode, rejectionReason, eventIds, locator, version, null, createdAt,
                leaseUntil);
    }

    public CommandRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("illegal command type: " + type);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("idempotencyKey is longer than 255 characters");
        }
        if (payloadFingerprint == null || !FINGERPRINT.matcher(payloadFingerprint).matches()) {
            throw new IllegalArgumentException("illegal payload fingerprint: " + payloadFingerprint);
        }
        issuedAt = issuedAt.truncatedTo(ChronoUnit.MICROS);
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        leaseUntil = leaseUntil == null ? null : leaseUntil.truncatedTo(ChronoUnit.MICROS);

        Map<String, Object> payloadCopy = new LinkedHashMap<>(payload);
        payload = Collections.unmodifiableMap(payloadCopy);
        eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        if (targetRef != null) {
            if (targetRef.isBlank()) {
                throw new IllegalArgumentException("targetRef must not be blank");
            }
            if (targetRef.length() > 255) {
                throw new IllegalArgumentException("targetRef is longer than 255 characters");
            }
        }

        switch (status) {
            case PENDING -> {
                requireTerminalFieldsAbsent(locator, targetRef, version, rejectionCode,
                        rejectionReason);
                if (leaseUntil == null) {
                    throw new IllegalArgumentException(
                            "pending command requires a lease");
                }
            }
            case ACCEPTED -> {
                if ((locator == null) == (targetRef == null)) {
                    throw new IllegalArgumentException(
                            "accepted command requires exactly one of locator or target_ref");
                }
                if (version == null || version < 1) {
                    throw new IllegalArgumentException(
                            "accepted command requires version >= 1");
                }
                if (rejectionCode != null || rejectionReason != null) {
                    throw new IllegalArgumentException(
                            "accepted command must not carry rejection fields");
                }
            }
            case REJECTED -> {
                if (locator != null || targetRef != null || version != null) {
                    throw new IllegalArgumentException(
                            "rejected command must not carry locator or version");
                }
                if (!isContractCode(rejectionCode)) {
                    throw new IllegalArgumentException(
                            "rejected command requires a contract error code");
                }
                if (rejectionReason == null || rejectionReason.isBlank()) {
                    throw new IllegalArgumentException(
                            "rejected command requires a rejection reason");
                }
            }
            default -> throw new IllegalArgumentException("unknown status: " + status);
        }
    }

    /**
     * 新建一条待处理记录。
     *
     * @param id                 命令 ID
     * @param type               命令类型
     * @param issuedAt           签发时间
     * @param issuer             发起者
     * @param payload            载荷
     * @param idempotencyKey     幂等键
     * @param payloadFingerprint 载荷指纹
     * @param createdAt          记录创建时间
     * @return 待处理记录
     */
    public static CommandRecord pending(UUID id, String type, Instant issuedAt, UUID issuer,
                                        Map<String, Object> payload, String idempotencyKey,
                                        String payloadFingerprint, Instant createdAt) {
        return pending(id, type, issuedAt, issuer, payload, idempotencyKey,
                payloadFingerprint, createdAt, createdAt.plusSeconds(60));
    }

    /**
     * 新建一条带租约的待处理记录。
     *
     * @param id                 命令 ID
     * @param type               命令类型
     * @param issuedAt           签发时间
     * @param issuer             发起者
     * @param payload            载荷
     * @param idempotencyKey     幂等键
     * @param payloadFingerprint 载荷指纹
     * @param createdAt          记录创建时间
     * @param leaseUntil         租约到期时间
     * @return 待处理记录
     */
    public static CommandRecord pending(UUID id, String type, Instant issuedAt, UUID issuer,
                                        Map<String, Object> payload, String idempotencyKey,
                                        String payloadFingerprint, Instant createdAt,
                                        Instant leaseUntil) {
        return new CommandRecord(id, type, issuedAt, issuer, payload, idempotencyKey,
                payloadFingerprint, CommandStatus.PENDING, null, null, List.of(), null, null, null,
                createdAt, leaseUntil);
    }

    /**
     * 转为接受终态。
     *
     * @param locator  写入的聚合根 ID
     * @param version  对象版本
     * @param eventIds 产生的事件 ID
     * @return 接受态记录
     */
    public CommandRecord accepted(UUID locator, long version, List<UUID> eventIds) {
        return new CommandRecord(id, type, issuedAt, issuer, payload, idempotencyKey,
                payloadFingerprint, CommandStatus.ACCEPTED, null, null, eventIds, locator,
                version, null, createdAt, leaseUntil);
    }

    /**
     * 转为接受终态，使用事件溯源命令的聚合标识文本。
     *
     * @param targetRef 聚合标识的规范文本（{@code IdCodec.encode}）
     * @param version   聚合版本
     * @param eventIds  产生的事件 ID
     * @return 接受态记录
     */
    public CommandRecord accepted(String targetRef, long version, List<UUID> eventIds) {
        return new CommandRecord(id, type, issuedAt, issuer, payload, idempotencyKey,
                payloadFingerprint, CommandStatus.ACCEPTED, null, null, eventIds, null,
                version, targetRef, createdAt, leaseUntil);
    }

    /**
     * 转为拒绝终态。
     *
     * @param code   契约错误码
     * @param reason 拒绝原因
     * @return 拒绝态记录
     */
    public CommandRecord rejected(ContractErrorCode code, String reason) {
        Objects.requireNonNull(code, "code");
        return new CommandRecord(id, type, issuedAt, issuer, payload, idempotencyKey,
                payloadFingerprint, CommandStatus.REJECTED, code.getCode(), reason, List.of(),
                null, null, null, createdAt, leaseUntil);
    }

    /**
     * 是否已有终态。
     *
     * @return {@code true} 表示接受或拒绝
     */
    public boolean isTerminal() {
        return status != CommandStatus.PENDING;
    }

    private static boolean isContractCode(String code) {
        if (code == null) {
            return false;
        }
        for (ContractErrorCode candidate : ContractErrorCode.values()) {
            if (candidate.getCode().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static void requireTerminalFieldsAbsent(UUID locator, String targetRef, Long version,
                                                    String rejectionCode,
                                                    String rejectionReason) {
        if (locator != null || targetRef != null || version != null) {
            throw new IllegalArgumentException("pending command must not carry locator or version");
        }
        if (rejectionCode != null || rejectionReason != null) {
            throw new IllegalArgumentException("pending command must not carry rejection fields");
        }
    }
}
