package cn.byteforce.seedj.starter.eis.authz;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzResolution;

/**
 * 授权解析操作面（本地引擎与远程缓存客户端共同实现）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EisAuthzOperations {

    /**
     * 解析主体在时点的有效角色与权限。
     *
     * @param principalRef 主体引用（ID 或 key）
     * @param asOf         业务时点
     * @param recordedAsOf 系统时点（可空）
     * @param scopeFilter  作用域过滤（JSON 对象；可空）
     * @return 解析结果
     */
    EisAuthzResolution resolve(String principalRef, Instant asOf, Instant recordedAsOf, JsonNode scopeFilter);

    /**
     * 检查权限。
     *
     * @param principalRef 主体引用
     * @param permission   权限串
     * @param asOf         业务时点
     * @param recordedAsOf 系统时点（可空）
     * @return 判定结果
     */
    EisAuthzDecision check(String principalRef, String permission, Instant asOf, Instant recordedAsOf);
}
