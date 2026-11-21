package cn.byteforce.seedj.iam;

import java.util.Set;

/** Effective authorization of a user after direct, group, and inherited roles. */
public record IamSubjectAuthorization(
        Set<String> roles, Set<String> permissions, Set<String> groups) {

    public IamSubjectAuthorization {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }

    public static IamSubjectAuthorization empty() {
        return new IamSubjectAuthorization(Set.of(), Set.of(), Set.of());
    }
}
