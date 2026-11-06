package cn.byteforce.seedj.starter.iam.token;

import cn.byteforce.seedj.iam.IamSubjectAuthorization;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.starter.iam.config.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Injects IAM claims into access tokens and ID tokens. Permissions are optional
 * because large permission sets belong behind introspection, not in every token.
 */
public class IamTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final IamUserRepository users;
    private final IamAuthorizationService authorization;
    private final IamProperties properties;
    private final String tenantId;

    public IamTokenCustomizer(IamUserRepository users, IamAuthorizationService authorization,
                              IamProperties properties, String tenantId) {
        this.users = users;
        this.authorization = authorization;
        this.properties = properties;
        this.tenantId = tenantId;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.getValue()
                .equals(context.getTokenType().getValue());
        boolean idToken = OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
        if (!accessToken && !idToken) {
            return;
        }

        Authentication principal = context.getPrincipal();
        String username = principal == null ? "" : principal.getName();
        IamUser user = users.findByUserName(tenantId, username).orElse(null);

        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        Set<String> amr = new LinkedHashSet<>();
        String acr = null;
        Instant authTime = null;

        if (user != null) {
            IamSubjectAuthorization auth = authorization.effective(user.tenantId(), user.id());
            roles.addAll(auth.roles());
            permissions.addAll(auth.permissions());
            groups.addAll(auth.groups());
            context.getClaims().claim("sub", user.id());
            context.getClaims().claim("user_id", user.id());
            context.getClaims().claim("preferred_username", user.userName());
            context.getClaims().claim("tenant_id", user.tenantId());
        } else if (principal != null) {
            for (GrantedAuthority granted : principal.getAuthorities()) {
                String value = granted.getAuthority();
                if (value.startsWith("ROLE_")) {
                    roles.add(value.substring("ROLE_".length()));
                } else if (value.startsWith("PERM_")) {
                    permissions.add(value.substring("PERM_".length()));
                } else if (value.startsWith("GROUP_")) {
                    groups.add(value.substring("GROUP_".length()));
                } else if (value.startsWith("AMR_")) {
                    amr.add(value.substring("AMR_".length()));
                } else if (value.startsWith("ACR_")) {
                    acr = value.substring("ACR_".length());
                } else if (value.startsWith("AUTH_TIME_")) {
                    try {
                        authTime = Instant.ofEpochSecond(Long.parseLong(value.substring(10)));
                    } catch (NumberFormatException ignored) {
                        // ignore malformed internal authority
                    }
                }
            }
        }
        if (!roles.isEmpty()) {
            context.getClaims().claim("roles", List.copyOf(roles));
        }
        if (accessToken && properties.token().includePermissions() && !permissions.isEmpty()) {
            context.getClaims().claim("permissions", List.copyOf(permissions));
        }
        if (!groups.isEmpty()) {
            context.getClaims().claim("groups", List.copyOf(groups));
        }
        if (amr.isEmpty()) {
            amr.add("pwd");
        }
        context.getClaims().claim("amr", List.copyOf(amr));
        context.getClaims().claim("acr", acr == null ? "urn:seedj:acr:pwd" : acr);
        if (context.getClaims().build().getClaim("auth_time") == null) {
            context.getClaims().claim("auth_time",
                    java.util.Date.from(authTime == null ? Instant.now() : authTime));
        }

        if (accessToken && context.getAuthorization() != null) {
            Object deviceId = context.getAuthorization().getAttribute("device_id");
            if (deviceId instanceof String text && !text.isBlank()) {
                context.getClaims().claim("device_id", text);
            }
        }

        if (accessToken) {
            context.getJwsHeader().type("at+jwt");
            String audience = properties.token().resourceAudience();
            if (audience != null && !audience.isBlank()) {
                context.getClaims().audience(List.of(audience));
            }
        }
    }
}
