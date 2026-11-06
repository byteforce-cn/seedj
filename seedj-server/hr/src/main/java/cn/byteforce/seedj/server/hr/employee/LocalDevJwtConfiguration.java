package cn.byteforce.seedj.server.hr.employee;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * local profile 的 JwtDecoder 占位：业务安全链需要该 bean，但本地没有 IdP。
 *
 * <p>解码必然失败（JwtException），因此不能用来伪造令牌；dev auth 关闭时它只用于让
 * 上下文可启动并验证 401 路径。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalDevJwtConfiguration {

    /**
     * 占位解码器。
     *
     * @return 永远抛异常的解码器
     */
    @Bean
    public JwtDecoder localDevJwtDecoder() {
        return token -> {
            throw new JwtException("local dev auth does not accept JWTs");
        };
    }
}
