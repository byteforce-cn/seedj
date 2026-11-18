package cn.byteforce.seedj.ai;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 命令引用：账本与 token 里“要执行什么”的最小描述，不含载荷。
 *
 * <p>载荷只以 {@code argsHash} 的形式出现在账本与 token 里：账本存原文会把敏感数据搬进审计存储，token 里塞载荷会让 token 变长且难以轮换。{@code
 * targets} 与 {@code expectedVersion} 参与指纹比对，守卫据此拒绝“换了对象”或 “换了版本”的重放。
 *
 * @param type 命令类型（与 {@code CommandCatalog} 的注册类型一致）
 * @param targets 目标对象标识；{@code null} 视为空列表
 * @param expectedVersion 期望的对象版本；{@code null} 表示调用方未声明版本
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandRef(String type, List<UUID> targets, Long expectedVersion) {

    /**
     * 校验命令类型并归一化目标列表。
     *
     * @throws NullPointerException {@code type} 为 {@code null}
     * @throws IllegalArgumentException {@code type} 为空白
     */
    public CommandRef {
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
