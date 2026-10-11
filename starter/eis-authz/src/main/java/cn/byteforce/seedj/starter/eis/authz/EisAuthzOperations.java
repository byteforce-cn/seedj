package cn.byteforce.seedj.starter.eis.authz;

import java.time.Instant;
import java.util.List;

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
    /**
     * 检查权限（含属性条件与可选调用方快照）。
     *
     * <p>缺省实现仅支持无条件的原有语义；声明了属性条件而实现未覆盖时
     * fail-closed（抛 {@link IllegalStateException}），绝不静默放行。</p>
     *
     * @param principalRef     主体引用（ID 或 key）
     * @param permission       权限串
     * @param asOf             业务时点
     * @param recordedAsOf     系统时点（可空）
     * @param conditions       属性条件（可空/空 = 不涉及属性）
     * @param providedSnapshot 调用方已拉取的快照（可空 = 判定方自行拉取）
     * @return 判定结果
     */
    default EisAuthzDecision check(String principalRef, String permission, Instant asOf, Instant recordedAsOf,
                                   List<EisAuthzAttributeCondition> conditions,
                                   EisAuthzAttributeSnapshot providedSnapshot) {
        if (conditions != null && !conditions.isEmpty()) {
            throw new IllegalStateException("授权操作实现未支持属性条件判定: " + getClass().getName());
        }
        return check(principalRef, permission, asOf, recordedAsOf);
    }
}
