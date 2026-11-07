package cn.byteforce.seedj.core.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 调用方身份快照：业务代码唯一需要认识的安全类型。
 *
 * <p>刻意设计成 {@code record}：测试直接 {@code new}，不需要 mock，也不需要 启动 Spring Security 上下文。
 *
 * <pre>{@code
 * Caller alice = new Caller("u-1", "alice", "default",
 *         Set.of("ADMIN"), Set.of("order:read"), Set.of(), Set.of("pwd"),
 *         AccessTokenRules.DEFAULT_ACR, Map.of());
 * assertThat(alice.hasRole("ADMIN")).isTrue();
 * }</pre>
 *
 * <p>字段来源与 {@code IamSubject} 保持一致，但两者职责不同：{@code IamSubject} 是策略决策点的输入快照，{@code Caller}
 * 是业务服务的只读调用方视图。
 *
 * @param id 稳定主体标识（JWT {@code user_id}，缺失时回落 {@code sub}）
 * @param username 登录名（JWT {@code sub}）
 * @param tenantId 租户；空白回落 {@link #DEFAULT_TENANT}
 * @param roles 角色名，不带 {@code ROLE_} 前缀
 * @param permissions 权限名，不带 {@code PERM_} 前缀
 * @param groups 组名，不带 {@code GROUP_} 前缀
 * @param amr 认证方法引用（RFC 8176），至少包含 {@link #DEFAULT_AMR}
 * @param acr 认证上下文类引用；空白回落 {@link #DEFAULT_ACR}
 * @param attributes 其余令牌声明，供 ABAC 使用；不得包含密码、OTP、密钥材料
 * @author ByteForce
 * @since 1.0.0
 */
public record Caller(
        String id,
        String username,
        String tenantId,
        Set<String> roles,
        Set<String> permissions,
        Set<String> groups,
        Set<String> amr,
        String acr,
        Map<String, Object> attributes) {

    /** 未提供租户时的默认值，与 {@code IamUser.DEFAULT_TENANT} 一致。 */
    public static final String DEFAULT_TENANT = "default";

    /** 未提供 amr 时的默认值：密码认证。 */
    public static final String DEFAULT_AMR = "pwd";

    /** 未提供 acr 时的默认值。 */
    public static final String DEFAULT_ACR = "urn:seedj:acr:pwd";

    public Caller {
        id = id == null ? "" : id;
        username = username == null ? "" : username;
        tenantId = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        amr = amr == null || amr.isEmpty() ? Set.of(DEFAULT_AMR) : Set.copyOf(amr);
        acr = acr == null || acr.isBlank() ? DEFAULT_ACR : acr;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** 是否拥有角色（大小写敏感，与 {@code hasRole} 的 Spring 语义不同：不要带前缀）。 */
    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }

    /** 是否拥有权限（不带 {@code PERM_} 前缀）。 */
    public boolean hasPermission(String permission) {
        return permission != null && permissions.contains(permission);
    }

    /** 是否属于某个组（不带 {@code GROUP_} 前缀）。 */
    public boolean hasGroup(String group) {
        return group != null && groups.contains(group);
    }

    /** 主体标识；空串视为缺失。 */
    public Optional<String> idIfPresent() {
        return id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    /**
     * 把集合型属性读成字符串列表；缺失或非集合返回空列表。
     *
     * <p>业务代码不需要直接 {@code attributes().get(...)} 再判断类型。只做展示/事实提取， 不做强制类型转换；需要结构化属性时在 core 增加明确类型。
     *
     * @param name 属性名
     * @return 字符串列表
     */
    public List<String> attributeStrings(String name) {
        Object raw = attributes.get(name);
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }
}
