package cn.byteforce.seedj.iam;


import java.util.Objects;
import java.util.Set;

/**
 * ABAC policy. {@code actions} and {@code resources} support exact values and
 * trailing {@code *} wildcards; matching is otherwise case-sensitive.
 */
public record IamPolicy(
        String id,
        String tenantId,
        String name,
        IamPolicyEffect effect,
        int priority,
        Set<String> actions,
        Set<String> resources,
        PolicyCondition condition,
        boolean enabled,
        long version) {

    public IamPolicy {
        id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        tenantId = tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
        name = Objects.requireNonNull(name, "name");
        effect = effect == null ? IamPolicyEffect.DENY : effect;
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        resources = resources == null ? Set.of() : Set.copyOf(resources);
        condition = condition == null ? PolicyCondition.Always.INSTANCE : condition;
        version = Math.max(0, version);
        if (actions.isEmpty() || resources.isEmpty()) {
            throw new IamException(IamErrorCode.POLICY_INVALID,
                    "policy actions and resources must not be empty");
        }
    }

    public static IamPolicy allow(String id, String tenantId, String name, Set<String> actions,
                                  Set<String> resources, PolicyCondition condition) {
        return new IamPolicy(id, tenantId, name,
                IamPolicyEffect.ALLOW, 0, actions, resources, condition, true, 0);
    }

    public static IamPolicy deny(String id, String tenantId, String name, Set<String> actions,
                                 Set<String> resources, PolicyCondition condition) {
        return new IamPolicy(id, tenantId, name,
                IamPolicyEffect.DENY, 1000, actions, resources, condition, true, 0);
    }

    public IamPolicy withEnabled(boolean enabled) {
        return new IamPolicy(id, tenantId, name, effect, priority, actions, resources,
                condition, enabled, version);
    }
}
