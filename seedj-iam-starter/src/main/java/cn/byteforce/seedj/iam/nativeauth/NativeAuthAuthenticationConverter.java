package cn.byteforce.seedj.iam.nativeauth;

import cn.byteforce.seedj.iam.security.NativeAuthGrantType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Parses only the native-auth grant; returns null for every other grant. */
public class NativeAuthAuthenticationConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        if (!NativeAuthGrantType.NATIVE_AUTH.getValue().equals(request.getParameter("grant_type"))) {
            return null;
        }
        Authentication principal = SecurityContextHolder.getContext().getAuthentication();
        RegisteredClient client = principal instanceof OAuth2ClientAuthenticationToken token
                ? token.getRegisteredClient() : null;
        Set<String> scopes = new LinkedHashSet<>();
        String[] requestedScopes = request.getParameterValues("scope");
        if (requestedScopes != null) {
            Arrays.stream(requestedScopes)
                    .flatMap(value -> Arrays.stream(value.split("\\s+")))
                    .filter(scope -> !scope.isBlank())
                    .forEach(scopes::add);
        }
        return new NativeAuthAuthenticationToken(principal, client,
                request.getParameter("username"), request.getParameter("password"),
                request.getParameter("mfa_token"), request.getParameter("otp"),
                request.getParameter("device_id"), request.getParameter("device_name"),
                request.getParameter("app_version"), scopes);
    }
}
