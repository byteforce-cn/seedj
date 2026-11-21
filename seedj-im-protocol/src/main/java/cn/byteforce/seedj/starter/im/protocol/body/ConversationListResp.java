package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 会话列表响应。
 *
 * @param conversations 会话视图
 * @param page 页码
 * @param size 每页条数
 * @param total 总条数
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConversationListResp(
        List<SessionSyncResp.ConversationView> conversations, int page, int size, long total) {

    public ConversationListResp {
        conversations = conversations == null ? List.of() : List.copyOf(conversations);
    }
}
