package cn.byteforce.seedj.iam;

import java.util.Map;
import java.util.Objects;

/** ABAC decision input. Resource attributes may be pre-resolved or resolved through SPI. */
public record IamAuthorizationRequest(
        IamSubject subject,
        String action,
        String resourceType,
        String resourceId,
        Map<String, Object> resourceAttributes,
        Map<String, Object> environment) {

    public IamAuthorizationRequest {
        subject = Objects.requireNonNull(subject, "subject");
        action = Objects.requireNonNull(action, "action");
        resourceType = Objects.requireNonNull(resourceType, "resourceType");
        resourceId = resourceId == null ? "" : resourceId;
        resourceAttributes = resourceAttributes == null ? Map.of() : Map.copyOf(resourceAttributes);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    public IamAuthorizationRequest withResourceAttributes(Map<String, Object> attributes) {
        return new IamAuthorizationRequest(
                subject, action, resourceType, resourceId, attributes, environment);
    }
}
