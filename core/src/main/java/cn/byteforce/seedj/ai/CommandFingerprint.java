package cn.byteforce.seedj.ai;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条命令的指纹输入：类型、载荷、目标与期望版本。
 *
 * <p>只承载计算指纹所需的四项，不携带幂等键与发起者——前者是调用方的重放凭据，后者不参与内容比对。 {@code targets} 顺序参与两个指纹的计算，构造时复制为不可变列表；{@code
 * payload} 的键序不影响结果。
 *
 * @param type 命令类型
 * @param payload 载荷
 * @param targets 目标对象 ID；{@code null} 视为空列表
 * @param expectedVersion 期望的对象版本；{@code null} 表示调用方未声明版本
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandFingerprint(
        String type, Map<String, Object> payload, List<UUID> targets, Long expectedVersion) {

    /**
     * 校验并归一化指纹输入。
     *
     * @throws IllegalArgumentException {@code type} 为空白
     * @throws NullPointerException {@code payload} 为 {@code null}
     */
    public CommandFingerprint {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        payload = Objects.requireNonNull(payload, "payload");
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
