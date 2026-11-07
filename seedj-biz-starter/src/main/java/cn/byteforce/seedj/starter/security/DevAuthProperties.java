package cn.byteforce.seedj.starter.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地开发认证配置（{@code seedj.biz.dev-auth.*}）。
 *
 * <p>宿主只声明本地调用方是谁，不再复制一份安全过滤器链；开关仍由
 * {@code DegradedModeGuard} 限制在 local/test。</p>
 *
 * @param enabled     是否启用本地开发认证
 * @param callerId    固定调用方 ID
 * @param name        调用方名称
 * @param permissions 授予的权限（不含 {@code PERM_} 前缀）
 * @param attributes  其余调用方属性（多值，例如 {@code org_units}）
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "seedj.biz.dev-auth")
public record DevAuthProperties(
        Boolean enabled,
        String callerId,
        String name,
        Set<String> permissions,
        Map<String, List<String>> attributes) {

    /** quickstart 固定调用方 ID。 */
    public static final String DEFAULT_CALLER_ID =
            "018f2e3a-0000-7000-8000-00000000ad11";

    /** 默认调用方名称。 */
    public static final String DEFAULT_NAME = "local-admin";

    public DevAuthProperties {
        enabled = enabled == null ? Boolean.FALSE : enabled;
        callerId = callerId == null || callerId.isBlank() ? DEFAULT_CALLER_ID : callerId;
        name = name == null || name.isBlank() ? DEFAULT_NAME : name;
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
