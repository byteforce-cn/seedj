package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 在线状态响应；同一个形状也作为状态变化通知（命令字 {@code PRESENCE_REQ}）下发。
 *
 * @param entries 每个 subject 一项
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PresenceResp(List<Entry> entries) {

    public PresenceResp {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * 单个主体的在线状态。
     *
     * @param subjectId IAM subject
     * @param online 是否有活动连接
     * @param devices 活动连接数
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Entry(String subjectId, boolean online, int devices) {}
}
