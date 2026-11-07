package cn.byteforce.seedj.iam.authz;

import java.util.List;

/**
 * 职责分离规则。
 *
 * @param id 规则 ID
 * @param name 名称
 * @param conflictingPermissions 冲突权限
 * @param scope 作用域比较维度
 * @param enforcement 强制方式
 * @author ByteForce
 * @since 1.0.0
 */
public record SodRule(
        String id,
        String name,
        List<String> conflictingPermissions,
        SodScope scope,
        SodEnforcement enforcement) {

    public SodRule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        conflictingPermissions =
                conflictingPermissions == null ? List.of() : List.copyOf(conflictingPermissions);
        if (conflictingPermissions.size() < 2) {
            throw new IllegalArgumentException("at least two conflicting permissions are required");
        }
        if (scope == null || enforcement == null) {
            throw new IllegalArgumentException("scope and enforcement are required");
        }
    }
}
