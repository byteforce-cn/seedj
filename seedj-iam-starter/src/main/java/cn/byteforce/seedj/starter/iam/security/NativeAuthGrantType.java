package cn.byteforce.seedj.starter.iam.security;

import org.springframework.security.oauth2.core.AuthorizationGrantType;

/** Non-standard first-party native login grant. */
public final class NativeAuthGrantType {

    public static final AuthorizationGrantType NATIVE_AUTH =
            new AuthorizationGrantType("urn:seedj:params:oauth:grant-type:native-auth");

    private NativeAuthGrantType() {
    }
}
