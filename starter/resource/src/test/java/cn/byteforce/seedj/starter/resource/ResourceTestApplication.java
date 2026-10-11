package cn.byteforce.seedj.starter.resource;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.List;

/**
 * 测试用资源服务应用：启用方法级安全，提供占位 {@code JwtDecoder}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@SpringBootApplication
@EnableMethodSecurity
public class ResourceTestApplication {

    /**
     * 占位 {@code JwtDecoder}：多数用例通过 {@code jwt()} 后处理器注入认证，不走真实解码；
     * 令牌值为 {@code authorities-claim} 时额外携带自定义权限 claim，用于验证
     * {@link SeedjJwtAuthenticationConverter} 在默认链中的实际消费。
     *
     * @return JwtDecoder
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            Jwt.Builder builder = Jwt.withTokenValue(token).header("alg", "none").subject("test-user");
            if ("authorities-claim".equals(token)) {
                builder.claim("authorities", List.of("SCOPE_admin"));
            }
            return builder.build();
        };
    }
}
