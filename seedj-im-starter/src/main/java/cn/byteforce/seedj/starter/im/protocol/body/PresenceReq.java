package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 在线状态查询：只返回与调用方同会话的 subject。
 *
 * @param subjectIds 要查询的 subject；空返回空列表
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PresenceReq(List<String> subjectIds) {

    public PresenceReq {
        subjectIds = subjectIds == null ? List.of() : List.copyOf(subjectIds);
    }
}
