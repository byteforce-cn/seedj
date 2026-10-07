package cn.byteforce.seedj.starter.authorization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthorizationServerProperties} 默认值与访问器契约测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class AuthorizationServerPropertiesTest {

    @Test
    void defaultsAreStable() {
        AuthorizationServerProperties properties = new AuthorizationServerProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getIssuer()).isNull();
        assertThat(properties.getClaims().getUserId()).isEqualTo("userId");
        assertThat(properties.getClaims().getTenantId()).isEqualTo("tenantId");
        assertThat(properties.getClaims().getUsername()).isEqualTo("preferred_username");
    }

    @Test
    void settersOverrideDefaults() {
        AuthorizationServerProperties properties = new AuthorizationServerProperties();
        properties.setEnabled(false);
        properties.setIssuer("https://auth.example.com");
        properties.getClaims().setUserId("uid");
        properties.getClaims().setTenantId("tid");
        properties.getClaims().setUsername("uname");

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getIssuer()).isEqualTo("https://auth.example.com");
        assertThat(properties.getClaims().getUserId()).isEqualTo("uid");
        assertThat(properties.getClaims().getTenantId()).isEqualTo("tid");
        assertThat(properties.getClaims().getUsername()).isEqualTo("uname");
    }
}
