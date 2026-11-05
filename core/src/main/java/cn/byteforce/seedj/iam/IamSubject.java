package cn.byteforce.seedj.iam;

import java.util.Map;
import java.util.Set;

/** Subject snapshot used by token customizers and the policy decision point. */
public record IamSubject(
        String userId,
        String username,
        String tenantId,
        Set<String> roles,
        Set<String> permissions,
        Set<String> groups,
        Set<String> amr,
        String acr,
        Map<String, Object> attributes) {

    public IamSubject {
        userId = userId == null ? "" : userId;
        username = username == null ? "" : username;
        tenantId = tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        amr = amr == null ? Set.of() : Set.copyOf(amr);
        acr = acr == null ? "urn:seedj:acr:pwd" : acr;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static IamSubject from(IamUser user, IamSubjectAuthorization authorization) {
        IamSubjectAuthorization auth = authorization == null
                ? IamSubjectAuthorization.empty() : authorization;
        return new IamSubject(user.id(), user.userName(), user.tenantId(), auth.roles(),
                auth.permissions(), auth.groups(), Set.of("pwd"), "urn:seedj:acr:pwd",
                user.attributes());
    }
}
