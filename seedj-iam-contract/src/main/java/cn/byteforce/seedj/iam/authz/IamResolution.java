package cn.byteforce.seedj.iam.authz;

import java.util.List;

/**
 * 时点授权解析结果。
 *
 * @param roles 角色 code 列表
 * @param permissions 权限列表
 * @param sodWarnings 命中的 warn 级 SoD 规则
 * @author ByteForce
 * @since 1.0.0
 */
public record IamResolution(
        List<String> roles, List<String> permissions, List<String> sodWarnings) {

    public IamResolution {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        sodWarnings = sodWarnings == null ? List.of() : List.copyOf(sodWarnings);
    }
}
