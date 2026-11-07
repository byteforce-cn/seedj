package cn.byteforce.seedj.cqrs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一条待受理的命令：HTTP/消息边界解析后的意图。
 *
 * <p>命令 ID、签发时间与状态由运行时分配；本类型只携带调用方给出的类型、载荷、 幂等键、目标对象与发起者。{@code targets} 参与幂等指纹，顺序敏感。
 *
 * @param type 命令类型
 * @param payload 载荷
 * @param idempotencyKey 幂等键
 * @param targets 目标对象 ID
 * @param issuer 发起者
 * @param expectedVersion 期望的对象版本；{@code null} 表示不校验
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandSubmission(
        String type,
        Map<String, Object> payload,
        String idempotencyKey,
        List<UUID> targets,
        UUID issuer,
        Long expectedVersion) {

    private static final Pattern TYPE = Pattern.compile("^[a-z][a-z0-9_.]*$");

    public CommandSubmission {
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("illegal command type: " + type);
        }
        Objects.requireNonNull(payload, "payload");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("idempotencyKey is longer than 255 characters");
        }
        Objects.requireNonNull(issuer, "issuer");
        targets = targets == null ? List.of() : targets;
        for (UUID target : targets) {
            if (target == null) {
                throw new IllegalArgumentException("targets must not contain null");
            }
        }
        targets = List.copyOf(targets);
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        if (expectedVersion != null && expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be >= 0");
        }
    }

    /**
     * 兼容旧调用：不校验版本。
     *
     * @param type 命令类型
     * @param payload 载荷
     * @param idempotencyKey 幂等键
     * @param targets 目标对象 ID
     * @param issuer 发起者
     */
    public CommandSubmission(
            String type,
            Map<String, Object> payload,
            String idempotencyKey,
            List<UUID> targets,
            UUID issuer) {
        this(type, payload, idempotencyKey, targets, issuer, null);
    }
}
