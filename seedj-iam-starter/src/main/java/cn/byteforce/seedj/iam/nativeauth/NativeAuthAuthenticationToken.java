package cn.byteforce.seedj.iam.nativeauth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Set;

/** Raw/authenticated native-auth grant token. */
public class NativeAuthAuthenticationToken extends AbstractAuthenticationToken {

    private final Authentication clientPrincipal;
    private final RegisteredClient registeredClient;
    private final String username;
    private final String password;
    private final String mfaToken;
    private final String otp;
    private final String deviceId;
    private final String deviceName;
    private final String appVersion;
    private final Set<String> scopes;

    public NativeAuthAuthenticationToken(Authentication clientPrincipal,
                                         RegisteredClient registeredClient,
                                         String username, String password, String mfaToken,
                                         String otp, String deviceId, String deviceName,
                                         String appVersion, Set<String> scopes) {
        super(java.util.Collections.emptyList());
        this.clientPrincipal = clientPrincipal;
        this.registeredClient = registeredClient;
        this.username = username;
        this.password = password;
        this.mfaToken = mfaToken;
        this.otp = otp;
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.appVersion = appVersion;
        this.scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        setAuthenticated(false);
    }

    @Override
    public Object getPrincipal() {
        return username == null ? mfaToken : username;
    }

    @Override
    public Object getCredentials() {
        return password;
    }

    public Authentication clientPrincipal() {
        return clientPrincipal;
    }

    public RegisteredClient registeredClient() {
        return registeredClient;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String mfaToken() {
        return mfaToken;
    }

    public String otp() {
        return otp;
    }

    public String deviceId() {
        return deviceId;
    }

    public String deviceName() {
        return deviceName;
    }

    public String appVersion() {
        return appVersion;
    }

    public Set<String> scopes() {
        return scopes;
    }
}
