package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 会话列表请求：当前调用方所属会话，按最近更新倒序。
 *
 * @param page 页码，从 1 开始；空用 1
 * @param size 每页条数（1..200）；空用 20
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConversationListReq(Integer page, Integer size) {}
