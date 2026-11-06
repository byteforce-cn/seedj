package cn.byteforce.seedj.starter.iam.token;

import cn.byteforce.seedj.iam.IamSubjectAuthorization;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Maps IAM/SCIM attributes to UserInfo claims. */
public class IamUserInfoMapper implements Function<OidcUserInfoAuthenticationContext, OidcUserInfo> {

    private final IamUserRepository users;
    private final IamAuthorizationService authorization;
    private final String tenantId;

    public IamUserInfoMapper(IamUserRepository users, IamAuthorizationService authorization,
                             String tenantId) {
        this.users = users;
        this.authorization = authorization;
        this.tenantId = tenantId;
    }

    @Override
    public OidcUserInfo apply(OidcUserInfoAuthenticationContext context) {
        String principalName = context.getAuthorization().getPrincipalName();
        Map<String, Object> claims = new LinkedHashMap<>();
        IamUser user = users.findByUserName(tenantId, principalName).orElse(null);
        claims.put("sub", user == null ? principalName : user.id());
        if (user == null) {
            return new OidcUserInfo(claims);
        }
        claims.put("preferred_username", user.userName());
        if (user.displayName() != null) {
            claims.put("name", user.displayName());
        }
        if (user.email() != null) {
            claims.put("email", user.email());
            claims.put("email_verified", false);
        }
        claims.put("tenant_id", user.tenantId());
        IamSubjectAuthorization auth = authorization.effective(user.tenantId(), user.id());
        if (!auth.roles().isEmpty()) {
            claims.put("roles", List.copyOf(auth.roles()));
        }
        if (!auth.groups().isEmpty()) {
            claims.put("groups", List.copyOf(auth.groups()));
        }
        if (!auth.permissions().isEmpty()) {
            claims.put("permissions", List.copyOf(auth.permissions()));
        }
        return new OidcUserInfo(claims);
    }
}
