package cn.byteforce.seedj.server.hr.employee;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 本地测试的 JwtDecoder 占位：业务安全链需要该 bean，但测试没有 IdP。
 *
 * <p>只在 {@code dev-auth=false} 的安全链测试里导入；生产与 dev-auth 模式都不装配。</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class LocalDevJwtConfiguration {

    @Bean
    public JwtDecoder localDevJwtDecoder() {
        return token -> {
            throw new JwtException("local dev auth does not accept JWTs");
        };
    }
}
