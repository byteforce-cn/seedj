package cn.byteforce.seedj.iam.authz;

/**
 * 权限到声明条件的绑定（来自扩展 manifest）。
 *
 * @param permission 权限名
 * @param condition  条件
 * @author ByteForce
 * @since 1.0.0
 */
public record IamPermissionCondition(String permission, IamCondition condition) {

    public IamPermissionCondition {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("permission must not be blank");
        }
        if (condition == null) {
            throw new IllegalArgumentException("condition is required");
        }
    }
}
