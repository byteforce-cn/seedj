package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 增量补拉响应。
 *
 * @param messages 消息列表，按序号升序
 * @param nextCursor 下一页游标；无消息时等于请求游标
 * @param hasMore 是否还有下一页
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OfflinePullResp(List<ChatDeliver> messages, long nextCursor, boolean hasMore) {

    public OfflinePullResp {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
