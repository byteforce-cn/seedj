package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 成员列表请求：看当前成员，或看某个序号时刻的成员。
 *
 * @param conversationId 会话标识
 * @param atSeq 时点序号；空表示当前成员
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MemberListReq(String conversationId, Long atSeq) {}
