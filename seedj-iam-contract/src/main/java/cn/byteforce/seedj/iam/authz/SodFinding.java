package cn.byteforce.seedj.iam.authz;

import java.util.List;

/**
 * 一条 SoD 冲突。
 *
 * @param ruleId 规则 ID
 * @param enforcement 强制方式
 * @param permissions 冲突权限
 * @author ByteForce
 * @since 1.0.0
 */
public record SodFinding(String ruleId, SodEnforcement enforcement, List<String> permissions) {

    public SodFinding {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
