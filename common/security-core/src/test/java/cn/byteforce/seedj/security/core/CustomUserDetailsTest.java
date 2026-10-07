package cn.byteforce.seedj.security.core;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CustomUserDetails} 契约测试：身份字段透出与 {@code UserDetails} 默认语义。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CustomUserDetailsTest {

    private final List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final CustomUserDetails userDetails = new CustomUserDetails(
            42L, "alice", "pwd", "Alice", "alice@example.com", 7L, "R&D", authorities);

    @Test
    void exposesIdentityFields() {
        assertThat(userDetails.getUserId()).isEqualTo(42L);
        assertThat(userDetails.getUsername()).isEqualTo("alice");
        assertThat(userDetails.getPassword()).isEqualTo("pwd");
        assertThat(userDetails.getNickname()).isEqualTo("Alice");
        assertThat(userDetails.getEmail()).isEqualTo("alice@example.com");
        assertThat(userDetails.getTenantId()).isEqualTo(7L);
        assertThat(userDetails.getDepartment()).isEqualTo("R&D");
        assertThat(userDetails.getAuthorities()).isEqualTo(authorities);
    }

    @Test
    void accountFlagsDefaultToEnabled() {
        assertThat(userDetails.isAccountNonExpired()).isTrue();
        assertThat(userDetails.isAccountNonLocked()).isTrue();
        assertThat(userDetails.isCredentialsNonExpired()).isTrue();
        assertThat(userDetails.isEnabled()).isTrue();
    }
}
