package cn.byteforce.seedj.iam.config;

import org.springframework.core.env.Environment;

/** Resolves the issuer from {@code seedj.iam.issuer} or the Boot standard property. */
public class IamIssuerResolver {

    private final String issuer;

    public IamIssuerResolver(Environment environment, IamProperties properties) {
        String value = properties.issuer();
        if (value == null || value.isBlank()) {
            value = environment.getProperty("spring.security.oauth2.authorizationserver.issuer");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("IAM issuer is not configured: set seedj.iam.issuer "
                    + "(or spring.security.oauth2.authorizationserver.issuer) to the public HTTPS "
                    + "issuer URL, for example https://iam.example.com");
        }
        this.issuer = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public String issuer() {
        return issuer;
    }
}
