package cn.byteforce.seedj.starter.iam.security;

import cn.byteforce.seedj.infra.local.iam.InMemoryIamTokenRevoker;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/** Hides revoked principals from the in-memory SAS authorization store. */
public class IamRevocableAuthorizationService implements OAuth2AuthorizationService {

    private final OAuth2AuthorizationService delegate;
    private final InMemoryIamTokenRevoker revoker;

    public IamRevocableAuthorizationService(
            OAuth2AuthorizationService delegate, InMemoryIamTokenRevoker revoker) {
        this.delegate = delegate;
        this.revoker = revoker;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        delegate.save(authorization);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return visible(delegate.findById(id));
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return visible(delegate.findByToken(token, tokenType));
    }

    private OAuth2Authorization visible(OAuth2Authorization authorization) {
        if (authorization == null || revoker == null) {
            return authorization;
        }
        return revoker.isRevoked(authorization.getPrincipalName()) ? null : authorization;
    }
}
