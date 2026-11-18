package cn.byteforce.seedj.starter.ai.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 任务契约：一次 agent 循环的输入，也是决策面判越权的锚点。
 *
 * <p>{@code id} 是 {@code seedj.ai.contracts[].id} 的字符串形态；决策面按 UUID 解析它，因此它必须是 UUID
 * 字符串。{@code maxSteps} 是这次任务自己的上限，与 {@code seedj.ai.runtime.max-steps} 的部署上限取小（见
 * {@link BoundedLoop}）：任务可以更保守，不能更宽松。
 *
 * @param id 契约标识（UUID 字符串）
 * @param conversationId 会话标识
 * @param instructions 任务指令原文
 * @param maxSteps 本次任务的步数上限，至少 1
 * @author ByteForce
 * @since 1.0.0
 */
public record TaskContract(String id, UUID conversationId, String instructions, int maxSteps) {

    /**
     * 校验必填项与步数上限。
     *
     * @throws NullPointerException {@code id}、{@code conversationId} 或 {@code instructions}
     *     为 {@code null}
     * @throws IllegalArgumentException {@code id} 为空白，或 {@code maxSteps} 小于 1
     */
    public TaskContract {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(instructions, "instructions");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
    }
}
