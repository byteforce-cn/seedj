package cn.byteforce.seedj.security;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 一次授权请求：主体、动作、资源上下文与时点。
 *
 * <p>命令路径用 {@code action = permission}、{@code resourceType = objectType}；
 * 读路径用读权限与可选对象 ID；字段级策略用 {@code action = read/read.masked} 并把
 * 分类放进 {@code resourceAttributes}。</p>
 *
 * @param subject            授权主体
 * @param action             动作（命令权限、{@code read} 或 {@code read.masked}）
 * @param resourceType       资源类型（对象类型）
 * @param resourceId         资源 ID；命令创建时为 {@code null}
 * @param resourceAttributes 资源属性（ABAC 输入）
 * @param asOf               业务时点
 * @param context            请求上下文（环境事实）
 * @author ByteForce
 * @since 1.0.0
 */
public record AuthorizationRequest(AuthorizationSubject subject, String action,
                                   String resourceType, String resourceId,
                                   Map<String, Object> resourceAttributes, Instant asOf,
                                   Map<String, Object> context) {

    public AuthorizationRequest {
        Objects.requireNonNull(subject, "subject");
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("authorization action is required");
        }
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("authorization resourceType is required");
        }
        Objects.requireNonNull(asOf, "asOf");
        resourceAttributes = resourceAttributes == null ? Map.of()
                : Map.copyOf(resourceAttributes);
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
