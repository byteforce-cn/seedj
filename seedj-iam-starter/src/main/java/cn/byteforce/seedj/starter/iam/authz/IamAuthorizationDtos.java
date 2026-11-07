package cn.byteforce.seedj.starter.iam.authz;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 授权 HTTP 请求/响应 DTO：线格式固定为 snake_case。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IamAuthorizationDtos {

    private IamAuthorizationDtos() {}

    public record ResolveRequest(
            String principal,
            @JsonProperty("as_of") Instant asOf,
            Map<String, String> scope,
            AttributeContext attributes) {}

    public record CheckRequest(
            String principal,
            String permission,
            @JsonProperty("as_of") Instant asOf,
            AttributeContext attributes,
            @JsonProperty("resource_type") String resourceType,
            @JsonProperty("resource_id") String resourceId,
            @JsonProperty("resource_attributes") Map<String, Object> resourceAttributes) {

        /**
         * 兼容旧请求：没有资源上下文。
         *
         * @param principal 主体
         * @param permission 权限
         * @param asOf 业务时点
         * @param attributes 属性上下文
         */
        public CheckRequest(
                String principal, String permission, Instant asOf, AttributeContext attributes) {
            this(principal, permission, asOf, attributes, null, null, null);
        }
    }

    public record AttributeContext(
            @JsonProperty("as_of") Instant asOf,
            String source,
            @JsonProperty("attribute_state") String attributeState,
            Map<String, Object> snapshot) {}

    public record ResolveResponse(
            List<String> roles,
            List<String> permissions,
            @JsonProperty("sod_warnings") List<String> sodWarnings) {}

    public record CheckResponse(
            boolean allowed,
            String reason,
            @JsonProperty("reason_code") String reasonCode,
            @JsonProperty("attribute_state") String attributeState) {}

    public record AttributesResponse(
            String principal,
            @JsonProperty("as_of") Instant asOf,
            @JsonProperty("recorded_at") Instant recordedAt,
            @JsonProperty("expires_at") Instant expiresAt,
            String source,
            @JsonProperty("source_version") String sourceVersion,
            @JsonProperty("attribute_state") String attributeState,
            Map<String, Object> snapshot) {}
}
