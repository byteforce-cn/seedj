package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 编辑请求：只有发送者能改自己的消息，且已撤回的消息不可编辑。
 *
 * @param conversationId 会话标识
 * @param messageId 消息标识
 * @param body 新的消息体
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EditReq(String conversationId, String messageId, Map<String, Object> body) {

    public EditReq {
        body = body == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(body));
    }
}
