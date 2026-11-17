package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 会话同步请求：取会话列表与该设备的同步进度。
 *
 * @param sinceSeq 客户端旧游标；设备没有服务端记录时用它初始化
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SessionSyncReq(Long sinceSeq) {}
