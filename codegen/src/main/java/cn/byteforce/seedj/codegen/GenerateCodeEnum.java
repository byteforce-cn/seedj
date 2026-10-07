package cn.byteforce.seedj.codegen;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * 标记注解：为 CodeEnum 枚举生成 {@code <EnumName>Codec} 静态工厂（code ↔ 枚举解析）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface GenerateCodeEnum {

    /**
     * 生成类名后缀（默认 {@code Codec}，即 {@code <EnumName>Codec}）。
     *
     * @return 后缀
     */
    String suffix() default "Codec";
}