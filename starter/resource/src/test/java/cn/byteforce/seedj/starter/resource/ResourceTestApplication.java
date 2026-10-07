package cn.byteforce.seedj.starter.resource;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

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
     * 占位 JwtDecoder：MockMvc 测试通过 {@code jwt()} 后处理器注入认证，不走真实解码。
     *
     * @return JwtDecoder
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> Jwt.withTokenValue(token).header("alg", "none").subject("test-user").build();
    }
}
