package cn.byteforce.seedj.starter.authorization;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式启用授权服务装配。
 *
 * <p>常规场景无需使用：引入 {@code starter-authorization} 后自动配置经
 * {@code META-INF/spring/...AutoConfiguration.imports} 自动生效；本注解用于需要
 * 显式导入（如测试切片）的场景，可重复导入且幂等。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(AuthorizationServerAutoConfiguration.class)
public @interface EnableAuthorizationServer {
}
