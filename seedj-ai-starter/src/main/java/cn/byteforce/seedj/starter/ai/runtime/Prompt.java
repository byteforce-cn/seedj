package cn.byteforce.seedj.starter.ai.runtime;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一步的提示：模型看到的全部输入。
 *
 * <p>{@code observations} 是之前各步的一行观测（工具返回什么、哪一步没有执行），<b>只有本循环生成的描述文本</b>
 * ——工具返回体里的 token 从不进入观测（见 {@link BoundedLoop}）。因此提示可以安全地发给模型与写进日志。
 *
 * @param taskContractId 任务契约标识（决策面按 UUID 解析）
 * @param conversationId 会话标识
 * @param step 会话内步序，从 1 开始
 * @param instructions 任务指令原文
 * @param observations 之前各步的观测文本；{@code null} 视为空列表
 * @author ByteForce
 * @since 1.0.0
 */
public record Prompt(
        String taskContractId,
        UUID conversationId,
        int step,
        String instructions,
        List<String> observations) {

    /**
     * 校验必填项并归一化观测列表。
     *
     * @throws NullPointerException {@code taskContractId}、{@code conversationId} 或
     *     {@code instructions} 为 {@code null}
     * @throws IllegalArgumentException {@code taskContractId} 为空白，或 {@code step} 小于 1
     */
    public Prompt {
        Objects.requireNonNull(taskContractId, "taskContractId");
        if (taskContractId.isBlank()) {
            throw new IllegalArgumentException("taskContractId must not be blank");
        }
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(instructions, "instructions");
        if (step < 1) {
            throw new IllegalArgumentException("step must be positive");
        }
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
