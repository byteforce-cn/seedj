package cn.byteforce.seedj.it.fixture.iam;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * IAM 集成测试用户夹具。
 *
 * <p>只存在于 {@code seedj-it}，不进入 {@code seedj-server-iam} 的生产制品。
 */
@Configuration(proxyBeanMethods = false)
public class IamTestUsers {

    public static final String USERNAME = "alice";
    public static final String PASSWORD = "alice-password";

    /** 用于“同意”路径的用户（首次请求 read 作用域会出现同意页）。 */
    public static final String CONSENT_USERNAME = "carol";

    public static final String CONSENT_PASSWORD = "carol-password";

    /** 用于“拒绝同意”路径的用户，避免与同意状态互相污染。 */
    public static final String DENY_USERNAME = "dave";

    public static final String DENY_PASSWORD = "dave-password";

    /** 用于“只授予部分 scope”路径的用户。 */
    public static final String NARROW_USERNAME = "erin";

    public static final String NARROW_PASSWORD = "erin-password";

    /** 用于“只有 read、没有 openid”路径的用户。 */
    public static final String READ_USERNAME = "frank";

    public static final String READ_PASSWORD = "frank-password";

    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(
                User.withUsername(USERNAME).password("{noop}" + PASSWORD).roles("USER").build(),
                User.withUsername(CONSENT_USERNAME)
                        .password("{noop}" + CONSENT_PASSWORD)
                        .roles("USER")
                        .build(),
                User.withUsername(DENY_USERNAME)
                        .password("{noop}" + DENY_PASSWORD)
                        .roles("USER")
                        .build(),
                User.withUsername(NARROW_USERNAME)
                        .password("{noop}" + NARROW_PASSWORD)
                        .roles("USER")
                        .build(),
                User.withUsername(READ_USERNAME)
                        .password("{noop}" + READ_PASSWORD)
                        .roles("USER")
                        .build());
    }
}
