package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 输入中通知。
 *
 * @param conversationId 会话标识
 * @param subjectId 正在输入的 subject
 * @param typing {@code true} 开始输入，{@code false} 停止
 * @param ts 事件时间（epoch 毫秒）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TypingNotify(String conversationId, String subjectId, boolean typing, long ts) {}
