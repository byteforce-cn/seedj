package cn.byteforce.seedj.it.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 参数注解：注入 {@link cn.byteforce.seedj.pki.PkiProvider}，
 * 与 IAM / IM 内部使用的是同一份 CA 与状态文件。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ClusterPki {
}
