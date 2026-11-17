package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 离线/增量补拉请求。
 *
 * @param cursor 已同步到的全局序号（不含）；空视为 0
 * @param limit 单页上限；空用服务端配置
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OfflinePullReq(Long cursor, Integer limit) {}
