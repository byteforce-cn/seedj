package cn.byteforce.seedj.starter.iam.nativeauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** Carries the mfa_token and methods so the token endpoint can return them. */
public class NativeAuthMfaRequiredException extends OAuth2AuthenticationException {

    private final Map<String, Object> parameters;

    public NativeAuthMfaRequiredException(String mfaToken, List<String> methods) {
        super(new OAuth2Error("mfa_required", "multi-factor authentication is required", null));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mfa_token", mfaToken);
        values.put("mfa_methods", List.copyOf(methods));
        this.parameters = Map.copyOf(values);
    }

    public Map<String, Object> parameters() {
        return parameters;
    }
}
