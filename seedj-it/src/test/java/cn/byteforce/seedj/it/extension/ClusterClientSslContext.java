package cn.byteforce.seedj.it.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 参数注解：注入持有测试客户端证书（CN=test-client）的 {@link javax.net.ssl.SSLContext}。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ClusterClientSslContext {
}
