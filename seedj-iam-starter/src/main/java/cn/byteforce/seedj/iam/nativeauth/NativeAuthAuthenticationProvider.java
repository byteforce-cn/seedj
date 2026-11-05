package cn.byteforce.seedj.iam.nativeauth;

import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamMfaChallenge;
import cn.byteforce.seedj.iam.IamSubjectAuthorization;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.config.IamProperties;
import cn.byteforce.seedj.iam.identity.IamAuthorizationService;
import cn.byteforce.seedj.iam.identity.IamIdentityService;
import cn.byteforce.seedj.iam.identity.IamMfaChallengeService;
import cn.byteforce.seedj.iam.identity.IamTotpService;
import cn.byteforce.seedj.iam.security.NativeAuthGrantType;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * First-party username/password grant with optional TOTP. This is intentionally
 * narrower than ROPC: clients must explicitly register the custom grant, and the
 * ADR recommends browser+PKCE for third-party apps.
 */
public class NativeAuthAuthenticationProvider implements AuthenticationProvider {

    private final IamIdentityService identities;
    private final IamUserRepository users;
    private final IamAuthorizationService authorization;
    private final IamTotpService totp;
    private final IamMfaChallengeService challenges;
    private final OAuth2AuthorizationService authorizations;
    private final OAuth2TokenGenerator<?> tokenGenerator;
    private final IamProperties properties;
    private final IamLoginRateLimiter rateLimiter;
    private final SecurityAuditSink audit;
    private final IdGenerator<String> idGenerator;

    public NativeAuthAuthenticationProvider(IamIdentityService identities, IamUserRepository users,
                                            IamAuthorizationService authorization,
                                            IamTotpService totp, IamMfaChallengeService challenges,
                                            OAuth2AuthorizationService authorizations,
                                            OAuth2TokenGenerator<?> tokenGenerator,
                                            IamProperties properties,
                                            IamLoginRateLimiter rateLimiter, SecurityAuditSink audit,
                                            IdGenerator<String> idGenerator) {
        this.identities = identities;
        this.users = users;
        this.authorization = authorization;
        this.totp = totp;
        this.challenges = challenges;
        this.authorizations = authorizations;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.idGenerator = idGenerator;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        NativeAuthAuthenticationToken token = (NativeAuthAuthenticationToken) authentication;
        RegisteredClient client = token.registeredClient();
        if (client == null || token.clientPrincipal() == null) {
            throw error(OAuth2ErrorCodes.INVALID_CLIENT, "client authentication required");
        }
        if (!client.getAuthorizationGrantTypes().contains(NativeAuthGrantType.NATIVE_AUTH)) {
            throw error(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT,
                    "client is not allowed to use native-auth");
        }
        Set<String> scopes = validateScopes(client, token.scopes());
        String tenantId = properties.tenantId();
        String clientId = client.getClientId();

        IamUser user;
        boolean mfaUsed = false;
        boolean recoveryUsed = false;
        if (token.username() != null && !token.username().isBlank()) {
            try {
                rateLimiter.check(tenantId, token.username(), clientId);
            } catch (IamException e) {
                throw error("account_locked", "account temporarily locked");
            }
            try {
                user = identities.authenticate(tenantId, token.username(), token.password());
            } catch (IamException e) {
                rateLimiter.failure(tenantId, token.username(), clientId);
                audit(null, tenantId, token.username(), "native-auth", "FAILURE", e.getCode().name());
                throw map(e);
            }
            rateLimiter.success(tenantId, token.username(), clientId);

            if (totp != null && totp.isEnabled(user.id())) {
                if (token.mfaToken() == null || token.mfaToken().isBlank()) {
                    String mfaToken = challenges.create(user.id(), clientId, token.deviceId());
                    audit(user, tenantId, user.userName(), "native-auth", "MFA_REQUIRED", "");
                    throw new NativeAuthMfaRequiredException(mfaToken, List.of("totp"));
                }
                IamMfaChallenge challenge;
                try {
                    challenge = requireChallenge(token, clientId, user.id());
                } catch (IamException e) {
                    throw error("mfa_invalid", e.getMessage());
                }
                OtpResult result = verifyOtp(user, token.otp(), challenge);
                mfaUsed = true;
                recoveryUsed = result.recovery();
                challenges.consume(challenge);
            }
        } else if (token.mfaToken() != null && !token.mfaToken().isBlank()) {
            IamMfaChallenge challenge;
            try {
                challenge = challenges.require(token.mfaToken());
            } catch (IamException e) {
                throw error("mfa_invalid", e.getMessage());
            }
            user = users.findById(challenge.userId()).orElseThrow(
                    () -> error(OAuth2ErrorCodes.INVALID_GRANT, "invalid mfa challenge"));
            if (!challenge.clientId().equals(clientId)) {
                throw error(OAuth2ErrorCodes.INVALID_GRANT, "invalid mfa challenge");
            }
            OtpResult result = verifyOtp(user, token.otp(), challenge);
            mfaUsed = true;
            recoveryUsed = result.recovery();
            challenges.consume(challenge);
        } else {
            throw error(OAuth2ErrorCodes.INVALID_REQUEST, "username/password or mfa_token required");
        }

        List<GrantedAuthority> authorities = authorities(user, mfaUsed, recoveryUsed);
        UsernamePasswordAuthenticationToken userAuthentication =
                new UsernamePasswordAuthenticationToken(user.userName(), null, authorities);

        OAuth2Authorization authorizationToSave = OAuth2Authorization
                .withRegisteredClient(client)
                .principalName(user.userName())
                .authorizationGrantType(NativeAuthGrantType.NATIVE_AUTH)
                .authorizedScopes(scopes)
                .attribute(Principal.class.getName(), userAuthentication)
                .attribute("device_id", token.deviceId() == null ? "" : token.deviceId())
                .attribute("device_name", token.deviceName() == null ? "" : token.deviceName())
                .attribute("app_version", token.appVersion() == null ? "" : token.appVersion())
                .attribute("amr", mfaUsed ? List.of("pwd", recoveryUsed ? "recovery" : "totp")
                        : List.of("pwd"))
                .attribute("acr", recoveryUsed ? "urn:seedj:acr:recovery"
                        : mfaUsed ? "urn:seedj:acr:totp" : "urn:seedj:acr:pwd")
                .build();

        DefaultOAuth2TokenContext.Builder context = DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(userAuthentication)
                .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                .authorization(authorizationToSave)
                .authorizedScopes(scopes)
                .authorizationGrantType(NativeAuthGrantType.NATIVE_AUTH)
                .authorizationGrant(token);

        org.springframework.security.oauth2.core.OAuth2Token generatedAccess = tokenGenerator.generate(
                context.tokenType(OAuth2TokenType.ACCESS_TOKEN).build());
        OAuth2AccessToken accessToken;
        if (generatedAccess instanceof OAuth2AccessToken oauth2AccessToken) {
            accessToken = oauth2AccessToken;
        } else if (generatedAccess instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
            accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                    jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), scopes);
        } else {
            throw error(OAuth2ErrorCodes.SERVER_ERROR, "unable to generate access token");
        }
        OAuth2RefreshToken refreshToken = null;
        if (client.getAuthorizationGrantTypes().contains(
                org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN)) {
            refreshToken = (OAuth2RefreshToken) tokenGenerator.generate(
                    context.tokenType(OAuth2TokenType.REFRESH_TOKEN).build());
        }
        OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorizationToSave)
                .accessToken(accessToken);
        if (refreshToken != null) {
            builder.refreshToken(refreshToken);
        }
        Map<String, Object> additional = new LinkedHashMap<>();
        if (scopes.contains("openid")) {
            Object idToken = tokenGenerator.generate(context
                    .tokenType(new OAuth2TokenType(OidcParameterNames.ID_TOKEN)).build());
            if (idToken instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
                org.springframework.security.oauth2.core.oidc.OidcIdToken oidcIdToken =
                        new org.springframework.security.oauth2.core.oidc.OidcIdToken(
                                jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(),
                                jwt.getClaims());
                builder.token(oidcIdToken);
                additional.put(OidcParameterNames.ID_TOKEN, oidcIdToken.getTokenValue());
            } else if (idToken != null) {
                builder.token((org.springframework.security.oauth2.core.OAuth2Token) idToken);
                additional.put(OidcParameterNames.ID_TOKEN,
                        ((org.springframework.security.oauth2.core.OAuth2Token) idToken).getTokenValue());
            }
        }
        authorizations.save(builder.build());
        audit(user, tenantId, user.userName(), "native-auth", "SUCCESS", "");
        return new OAuth2AccessTokenAuthenticationToken(client, token.clientPrincipal(),
                accessToken, refreshToken, additional);
    }

    private IamMfaChallenge requireChallenge(NativeAuthAuthenticationToken token, String clientId,
                                             String userId) {
        IamMfaChallenge challenge = challenges.require(token.mfaToken());
        if (!challenge.userId().equals(userId) || !challenge.clientId().equals(clientId)) {
            throw error(OAuth2ErrorCodes.INVALID_GRANT, "invalid mfa challenge");
        }
        if (token.deviceId() != null && !token.deviceId().isBlank()
                && !challenge.deviceId().isBlank()
                && !challenge.deviceId().equals(token.deviceId())) {
            throw error(OAuth2ErrorCodes.INVALID_GRANT, "mfa challenge device mismatch");
        }
        return challenge;
    }

    private OtpResult verifyOtp(IamUser user, String otp, IamMfaChallenge challenge) {
        if (totp == null) {
            throw error("mfa_invalid", "TOTP is not enabled");
        }
        if (otp == null || otp.isBlank()) {
            challenges.failure(challenge);
            throw error("mfa_invalid", "otp is required");
        }
        try {
            totp.verify(user.id(), otp);
            return new OtpResult(false);
        } catch (IamException totpFailure) {
            try {
                totp.verifyRecoveryCode(user.id(), otp);
                return new OtpResult(true);
            } catch (IamException recoveryFailure) {
                challenges.failure(challenge);
                throw error("mfa_invalid", "invalid otp");
            }
        }
    }

    private List<GrantedAuthority> authorities(IamUser user, boolean mfaUsed, boolean recovery) {
        IamSubjectAuthorization auth = authorization.effective(user.tenantId(), user.id());
        List<GrantedAuthority> authorities = new ArrayList<>();
        auth.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        auth.permissions().forEach(permission ->
                authorities.add(new SimpleGrantedAuthority("PERM_" + permission)));
        auth.groups().forEach(group ->
                authorities.add(new SimpleGrantedAuthority("GROUP_" + group)));
        authorities.add(new SimpleGrantedAuthority("AMR_PWD"));
        if (mfaUsed) {
            authorities.add(new SimpleGrantedAuthority(recovery ? "AMR_RECOVERY" : "AMR_TOTP"));
        }
        authorities.add(new SimpleGrantedAuthority(recovery ? "ACR_urn:seedj:acr:recovery"
                : mfaUsed ? "ACR_urn:seedj:acr:totp" : "ACR_urn:seedj:acr:pwd"));
        authorities.add(new SimpleGrantedAuthority("AUTH_TIME_" + Instant.now().getEpochSecond()));
        return authorities;
    }

    private static Set<String> validateScopes(RegisteredClient client, Set<String> requested) {
        Set<String> scopes = new LinkedHashSet<>(requested);
        if (scopes.isEmpty()) {
            scopes.add("openid");
        }
        if (!client.getScopes().containsAll(scopes)) {
            throw error(OAuth2ErrorCodes.INVALID_SCOPE, "requested scope is not allowed");
        }
        return Set.copyOf(scopes);
    }

    private static OAuth2AuthenticationException map(IamException exception) {
        if (exception.getCode() == IamErrorCode.ACCOUNT_LOCKED
                || exception.getCode() == IamErrorCode.RATE_LIMITED) {
            return error("account_locked", "account temporarily locked");
        }
        return error(OAuth2ErrorCodes.INVALID_GRANT, "invalid credentials");
    }

    private static OAuth2AuthenticationException error(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
    }

    private void audit(IamUser user, String tenantId, String username, String action,
                       String outcome, String reason) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(new SecurityAuditEvent(idGenerator.id(), tenantId,
                    user == null ? "" : user.id(), action, "user",
                    user == null ? "" : user.id(), outcome, reason, Instant.now(),
                    Map.of("username", username == null ? "" : username)));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return NativeAuthAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private record OtpResult(boolean recovery) {
    }
}
