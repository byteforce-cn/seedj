package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 客户端确认：已收到（并持久化）到某个序号。
 *
 * @param conversationId 会话标识
 * @param seq 已收到的序号
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AckReq(String conversationId, long seq) {}
