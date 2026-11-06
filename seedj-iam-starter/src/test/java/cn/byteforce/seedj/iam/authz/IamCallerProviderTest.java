package cn.byteforce.seedj.iam.authz;

import cn.byteforce.seedj.core.security.Caller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IAM 宿主的 CallerProvider：从 SecurityContext 读取权限。
 */
class IamCallerProviderTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("PERM_ 前缀转为权限，未认证返回空")
    void mapsAuthoritiesAndHandlesAnonymous() {
        IamCallerProvider provider = new IamCallerProvider("default");

        assertThat(provider.find()).isEmpty();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("svc", "n/a",
                        List.of(new SimpleGrantedAuthority("PERM_hr.attributes.read"),
                                new SimpleGrantedAuthority("ROLE_internal"))));

        Caller caller = provider.find().orElseThrow();
        assertThat(caller.username()).isEqualTo("svc");
        assertThat(caller.hasPermission("hr.attributes.read")).isTrue();
        assertThat(caller.hasRole("internal")).isTrue();
    }
}
