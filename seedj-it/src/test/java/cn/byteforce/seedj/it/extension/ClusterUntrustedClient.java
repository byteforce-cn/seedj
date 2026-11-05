package cn.byteforce.seedj.it.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 参数注解：注入“信任 IAM CA、但出示非受信 CA 客户端证书”的 {@link javax.net.ssl.SSLContext}，
 * 用于验证 mTLS 的 trust store 校验链路。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ClusterUntrustedClient {
}
