package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 已读上报；服务端向同会话其它成员转发同形状的通知。
 *
 * @param conversationId 会话标识
 * @param seq 已读到的序号
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReadReceiptReq(String conversationId, long seq) {}
