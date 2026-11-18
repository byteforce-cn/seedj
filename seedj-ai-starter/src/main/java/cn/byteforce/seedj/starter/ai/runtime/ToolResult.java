package cn.byteforce.seedj.starter.ai.runtime;

import java.util.UUID;

/**
 * 一次工具调用的结果。
 *
 * <p>{@code ok} 由命令面的响应状态给出：2xx 是成功，其余（业务拒绝、幂等冲突、准入失败）都是失败，{@code body}
 * 原样保留供模型与运维看。{@code body} 是<b>外部系统返回体</b>，不是 token——调用方不能把它当成凭据转发。
 *
 * @param ok 调用是否成功
 * @param body 响应体；{@code null} 视为空串
 * @param commandId 命令标识；响应里没有时为空
 * @author ByteForce
 * @since 1.0.0
 */
public record ToolResult(boolean ok, String body, UUID commandId) {

    /**
     * 归一化响应体。
     *
     * @param ok 调用是否成功
     * @param body 响应体；{@code null} 视为空串
     * @param commandId 命令标识；可空
     */
    public ToolResult {
        body = body == null ? "" : body;
    }
}
