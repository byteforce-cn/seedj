package cn.byteforce.seedj.iam.abac;

import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamAuthorizationRequest;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamSubject;
import cn.byteforce.seedj.iam.IamSubjectAuthorization;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.config.IamProperties;
import cn.byteforce.seedj.iam.identity.IamAuthorizationService;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bean named {@code iam} for SpEL enforcement:
 * {@code @PreAuthorize("@iam.authorize(authentication, 'order', #id, 'read')")}.
 */
public class IamAuthorizationFacade {

    private final IamPolicyDecisionPoint pdp;
    private final IamUserRepository users;
    private final IamAuthorizationService authorization;
    private final String tenantId;

    public IamAuthorizationFacade(IamPolicyDecisionPoint pdp, IamUserRepository users,
                                  IamAuthorizationService authorization, IamProperties properties) {
        this.pdp = pdp;
        this.users = users;
        this.authorization = authorization;
        this.tenantId = properties.tenantId();
    }

    public boolean authorize(Authentication authentication, String resourceType,
                             String resourceId, String action) {
        IamSubject subject = subject(authentication);
        IamAuthorizationDecision decision = pdp.decide(new IamAuthorizationRequest(subject,
                action, resourceType, resourceId, Map.of(), Map.of()));
        return decision.allowed();
    }

    public void require(Authentication authentication, String resourceType, String resourceId,
                        String action) {
        if (!authorize(authentication, resourceType, resourceId, action)) {
            throw new IamException(IamErrorCode.FORBIDDEN,
                    "not authorized for " + action + " on " + resourceType + "/" + resourceId);
        }
    }

    private IamSubject subject(Authentication authentication) {
        if (authentication == null) {
            throw new IamException(IamErrorCode.FORBIDDEN, "authentication required");
        }
        String username = authentication.getName();
        IamUser user = users.findByUserName(tenantId, username)
                .orElseGet(() -> users.findById(username).orElse(null));
        if (user != null) {
            IamSubjectAuthorization auth = authorization.effective(user.tenantId(), user.id());
            Map<String, Object> attributes = new LinkedHashMap<>(user.attributes());
            if (authentication instanceof JwtAuthenticationToken jwt) {
                attributes.putAll(jwt.getToken().getClaims());
            }
            return new IamSubject(user.id(), user.userName(), user.tenantId(), auth.roles(),
                    auth.permissions(), auth.groups(), amr(authentication), acr(authentication),
                    attributes);
        }
        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            String value = granted.getAuthority();
            if (value.startsWith("ROLE_")) {
                roles.add(value.substring(5));
            } else if (value.startsWith("PERM_")) {
                permissions.add(value.substring(5));
            } else if (value.startsWith("GROUP_")) {
                groups.add(value.substring(6));
            }
        }
        String subjectId = username;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            attributes.putAll(jwt.getToken().getClaims());
            Object claim = jwt.getToken().getClaim("user_id");
            if (claim != null) {
                subjectId = String.valueOf(claim);
            }
            List<String> claimRoles = jwt.getToken().getClaimAsStringList("roles");
            if (claimRoles != null) {
                roles.addAll(claimRoles);
            }
            List<String> claimPermissions = jwt.getToken().getClaimAsStringList("permissions");
            if (claimPermissions != null) {
                permissions.addAll(claimPermissions);
            }
            List<String> claimGroups = jwt.getToken().getClaimAsStringList("groups");
            if (claimGroups != null) {
                groups.addAll(claimGroups);
            }
        }
        return new IamSubject(subjectId, username, tenantId, roles, permissions, groups,
                amr(authentication), acr(authentication), attributes);
    }

    private static Set<String> amr(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            List<String> amr = jwt.getToken().getClaimAsStringList("amr");
            if (amr != null && !amr.isEmpty()) {
                return Set.copyOf(amr);
            }
        }
        Set<String> amr = new LinkedHashSet<>();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (granted.getAuthority().startsWith("AMR_")) {
                amr.add(granted.getAuthority().substring(4));
            }
        }
        return amr.isEmpty() ? Set.of("pwd") : Set.copyOf(amr);
    }

    private static String acr(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String acr = jwt.getToken().getClaimAsString("acr");
            if (acr != null && !acr.isBlank()) {
                return acr;
            }
        }
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (granted.getAuthority().startsWith("ACR_")) {
                return granted.getAuthority().substring(4);
            }
        }
        return "urn:seedj:acr:pwd";
    }
}
