package cn.byteforce.seedj.it.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 指定当前测试需要启动的集群服务。
 * <p>不标注该注解，或 value 为空，等价于启动全部服务（向后兼容）。</p>
 * <p>只要点名了任意后端服务，IAM 会被自动加入（JWT issuer 依赖）。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface EnableClusterServices {

    /** 需要启动的服务名，例如 {"im", "hr"}。空 = 全部。 */
    String[] value() default {};
}
