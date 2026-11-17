package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 输入中上报：服务端向同会话其它成员转发 {@link TypingNotify}，不回包。
 *
 * @param conversationId 会话标识
 * @param typing {@code true} 开始输入，{@code false} 停止
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TypingReq(String conversationId, boolean typing) {}
