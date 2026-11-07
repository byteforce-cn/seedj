package cn.byteforce.seedj.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 授权主体快照：决策点的输入，不绑定具体身份提供方。
 *
 * <p>与 {@code IamSubject} 的区别：本类型属于契约层的授权 SPI，命令与读路径只依赖它；
 * {@code IamSubject} 是 IAM starter 内部策略引擎的输入。</p>
 *
 * @param id          稳定主体标识
 * @param username    登录名
 * @param tenantId    租户
 * @param roles       角色
 * @param permissions 权限
 * @param groups      组
 * @param amr         认证方法引用
 * @param acr         认证上下文类引用
 * @param attributes  其余属性（供 ABAC 使用）
 * @author ByteForce
 * @since 1.0.0
 */
public record AuthorizationSubject(String id, String username, String tenantId,
                                   Set<String> roles, Set<String> permissions,
                                   Set<String> groups, Set<String> amr, String acr,
                                   Map<String, Object> attributes) {

    public AuthorizationSubject {
        id = id == null ? "" : id;
        username = username == null ? "" : username;
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        amr = amr == null ? Set.of() : Set.copyOf(amr);
        acr = acr == null ? "" : acr;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * 从业务调用方快照转换。
     *
     * @param caller 调用方
     * @return 授权主体
     */
    public static AuthorizationSubject from(cn.byteforce.seedj.core.security.Caller caller) {
        Objects.requireNonNull(caller, "caller");
        return new AuthorizationSubject(caller.id(), caller.username(), caller.tenantId(),
                caller.roles(), caller.permissions(), caller.groups(), caller.amr(),
                caller.acr(), caller.attributes());
    }
}
