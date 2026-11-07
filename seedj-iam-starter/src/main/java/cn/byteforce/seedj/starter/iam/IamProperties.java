package cn.byteforce.seedj.starter.iam;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IAM configuration. Nested records are constructor-bound; every null is replaced with a safe
 * default in the compact constructor.
 */
@ConfigurationProperties(prefix = "seedj.iam")
public record IamProperties(
        Boolean enabled,
        String issuer,
        String tenantId,
        Store store,
        Admin admin,
        Token token,
        Mfa mfa,
        NativeAuth nativeAuth,
        Scim scim,
        Rbac rbac,
        Abac abac,
        Demo demo,
        Jwk jwk,
        Secret secret,
        List<Client> clients) {

    public enum Store {
        MEMORY,
        JDBC
    }

    /** Defaults for programmatic construction (tests, embedded use). */
    public static IamProperties defaults() {
        return new IamProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    public IamProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        store = store == null ? Store.MEMORY : store;
        admin = admin == null ? new Admin(null, null, null) : admin;
        token = token == null ? new Token(null, null, null, null, null) : token;
        mfa = mfa == null ? new Mfa(null, null) : mfa;
        nativeAuth = nativeAuth == null ? new NativeAuth(null, null, null, null) : nativeAuth;
        scim = scim == null ? new Scim(null, null) : scim;
        rbac = rbac == null ? new Rbac(null, null) : rbac;
        abac = abac == null ? new Abac(null, null) : abac;
        demo = demo == null ? new Demo(null) : demo;
        jwk = jwk == null ? new Jwk(null, null, null) : jwk;
        secret = secret == null ? new Secret(null, null) : secret;
        clients = clients == null ? List.of() : List.copyOf(clients);
    }

    public record Admin(String bootstrapUsername, String bootstrapPassword, String tenantId) {
        public Admin {
            tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        }
    }

    public record Token(
            Duration accessTtl,
            Duration refreshTtl,
            Duration authorizationCodeTtl,
            Boolean includePermissions,
            String resourceAudience) {
        public Token {
            accessTtl = accessTtl == null ? Duration.ofMinutes(15) : accessTtl;
            refreshTtl = refreshTtl == null ? Duration.ofDays(30) : refreshTtl;
            authorizationCodeTtl =
                    authorizationCodeTtl == null ? Duration.ofMinutes(5) : authorizationCodeTtl;
            includePermissions = includePermissions == null || includePermissions;
        }
    }

    public record Mfa(Totp totp, Boolean required) {
        public Mfa {
            totp = totp == null ? new Totp(null, null, null, null, null, null, null) : totp;
            required = required != null && required;
        }
    }

    public record Totp(
            Boolean enabled,
            String issuer,
            Integer digits,
            Duration period,
            Integer window,
            Integer recoveryCodes,
            String secretKey) {
        public Totp {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            issuer = issuer == null || issuer.isBlank() ? "Seedj" : issuer;
            digits = digits == null ? 6 : digits;
            period = period == null ? Duration.ofSeconds(30) : period;
            window = window == null ? 1 : window;
            recoveryCodes = recoveryCodes == null ? 10 : recoveryCodes;
            if (digits < 6 || digits > 10) {
                throw new IllegalArgumentException("seedj.iam.mfa.totp.digits must be 6..10");
            }
            if (period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("seedj.iam.mfa.totp.period must be positive");
            }
            if (window < 0 || window > 10) {
                throw new IllegalArgumentException("seedj.iam.mfa.totp.window must be 0..10");
            }
        }
    }

    public record NativeAuth(
            Boolean enabled, Boolean mfaRequired, Duration mfaTokenTtl, Integer maxAttempts) {
        public NativeAuth {
            enabled = enabled == null ? Boolean.FALSE : enabled;
            mfaRequired = mfaRequired == null ? Boolean.TRUE : mfaRequired;
            mfaTokenTtl = mfaTokenTtl == null ? Duration.ofMinutes(5) : mfaTokenTtl;
            maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        }
    }

    public record Scim(Boolean enabled, String basePath) {
        public Scim {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            basePath = basePath == null || basePath.isBlank() ? "/scim/v2" : basePath;
        }
    }

    public record Rbac(Boolean enabled, Duration cacheTtl) {
        public Rbac {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            cacheTtl = cacheTtl == null ? Duration.ofSeconds(30) : cacheTtl;
        }
    }

    public record Abac(Boolean enabled, Duration cacheTtl) {
        public Abac {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            cacheTtl = cacheTtl == null ? Duration.ofSeconds(30) : cacheTtl;
        }
    }

    public record Demo(Boolean enabled) {
        public Demo {
            enabled = enabled == null ? Boolean.FALSE : enabled;
        }
    }

    public record Jwk(String provider, String encryptionKey, Duration retireAfter) {
        public Jwk {
            provider = provider == null || provider.isBlank() ? "auto" : provider;
            retireAfter = retireAfter == null ? Duration.ofDays(30) : retireAfter;
        }
    }

    public record Secret(String key, String keyName) {
        public Secret {
            keyName = keyName == null || keyName.isBlank() ? "iam-aes" : keyName;
        }
    }

    public record Client(
            String clientId,
            String clientSecret,
            List<String> redirectUris,
            List<String> scopes,
            List<String> grantTypes,
            Boolean requireProofKey,
            Boolean requireConsent,
            String authenticationMethod,
            String x509SubjectDn) {
        public Client {
            scopes = scopes == null ? List.of("openid", "profile") : List.copyOf(scopes);
            redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
            grantTypes =
                    grantTypes == null
                            ? List.of("authorization_code", "refresh_token")
                            : List.copyOf(grantTypes);
            requireProofKey = requireProofKey == null || requireProofKey;
            requireConsent = requireConsent == null || requireConsent;
            authenticationMethod =
                    authenticationMethod == null || authenticationMethod.isBlank()
                            ? "client_secret_basic"
                            : authenticationMethod;
        }
    }
}
