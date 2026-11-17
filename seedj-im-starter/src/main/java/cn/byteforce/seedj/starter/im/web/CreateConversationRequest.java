package cn.byteforce.seedj.starter.im.web;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 建会话请求：{@code kind=single} 用 {@code peer_id}，{@code kind=group} 用 {@code title} 与 {@code
 * member_ids}。
 *
 * @param kind 会话类型：{@code single} / {@code group}
 * @param title 群名（群聊必填）
 * @param peerId 对端 subject（单聊必填）
 * @param memberIds 初始成员（群聊）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateConversationRequest(
        String kind, String title, String peerId, List<String> memberIds) {}
