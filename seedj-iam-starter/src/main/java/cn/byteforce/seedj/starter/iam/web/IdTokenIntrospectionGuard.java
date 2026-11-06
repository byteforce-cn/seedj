package cn.byteforce.seedj.starter.iam.web;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenIntrospection;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationToken;

/**
 * RFC 7662 只定义 access token / refresh token 的内省语义。SAS 默认实现按 token 值
 * 查找 {@code OAuth2Authorization}，会把同一授权里的 ID Token 也报告成 active=true。
 *
 * <p>这里在默认内省 provider 之前拦截 ID Token，返回 {@code {"active":false}}，
 * 避免 ID Token 被当成 Access Token 使用。</p>
 */
public final class IdTokenIntrospectionGuard implements AuthenticationProvider {

    private final OAuth2AuthorizationService authorizationService;

    public IdTokenIntrospectionGuard(OAuth2AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof OAuth2TokenIntrospectionAuthenticationToken request)) {
            return null;
        }
        OAuth2Authorization authorization = authorizationService.findByToken(request.getToken(), null);
        if (authorization == null) {
            return null;
        }
        OAuth2Authorization.Token<OidcIdToken> idToken = authorization.getToken(OidcIdToken.class);
        if (idToken == null || !request.getToken().equals(idToken.getToken().getTokenValue())) {
            return null;
        }
        return new OAuth2TokenIntrospectionAuthenticationToken(
                request.getToken(),
                (Authentication) request.getPrincipal(),
                OAuth2TokenIntrospection.builder(false).build());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2TokenIntrospectionAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
