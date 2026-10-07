package cn.byteforce.seedj.starter.resource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResourceServerProperties} 默认值与访问器契约测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class ResourceServerPropertiesTest {

    @Test
    void defaultsAreStable() {
        ResourceServerProperties properties = new ResourceServerProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getPermitPaths())
                .containsExactly("/error", "/actuator/health", "/actuator/health/**", "/actuator/info");
        assertThat(properties.getClaims().getUserId()).isEqualTo("userId");
        assertThat(properties.getClaims().getTenantId()).isEqualTo("tenantId");
        assertThat(properties.getClaims().getUsername()).isEqualTo("preferred_username");
        assertThat(properties.getClaims().getAuthorities()).isEqualTo("authorities");
    }

    @Test
    void settersOverrideDefaults() {
        ResourceServerProperties properties = new ResourceServerProperties();
        properties.setEnabled(false);
        properties.setPermitPaths(List.of("/public"));
        properties.getClaims().setUserId("uid");
        properties.getClaims().setTenantId("tid");
        properties.getClaims().setUsername("uname");
        properties.getClaims().setAuthorities("perms");

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getPermitPaths()).containsExactly("/public");
        assertThat(properties.getClaims().getUserId()).isEqualTo("uid");
        assertThat(properties.getClaims().getTenantId()).isEqualTo("tid");
        assertThat(properties.getClaims().getUsername()).isEqualTo("uname");
        assertThat(properties.getClaims().getAuthorities()).isEqualTo("perms");
    }
}
