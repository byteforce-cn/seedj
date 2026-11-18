package cn.byteforce.seedj.starter.ai.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 要发给命令面的一次调用：命令类型、未脱敏载荷、目标与期望版本。
 *
 * <p>载荷保持原文（允许 {@code null} 值、键序保留）：它参与决策面与守卫的指纹计算，脱敏只影响账本里可读的
 * {@code args}。{@code targets} 顺序敏感。
 *
 * @param commandType 命令类型
 * @param payload 未脱敏命令载荷；{@code null} 视为空对象
 * @param targets 目标对象；{@code null} 视为空列表
 * @param expectedVersion 期望对象版本；{@code null} 表示不校验
 * @author ByteForce
 * @since 1.0.0
 */
public record Call(
        String commandType, Map<String, Object> payload, List<UUID> targets, Long expectedVersion) {

    /**
     * 校验命令类型并归一化集合组件。
     *
     * @throws NullPointerException {@code commandType} 为 {@code null}
     * @throws IllegalArgumentException {@code commandType} 为空白
     */
    public Call {
        Objects.requireNonNull(commandType, "commandType");
        if (commandType.isBlank()) {
            throw new IllegalArgumentException("commandType must not be blank");
        }
        payload =
                payload == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
