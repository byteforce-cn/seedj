package cn.byteforce.seedj.starter.coord.ratelimiter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级限流（可选 AOP，按实例语义；C4）。
 *
 * <p>key 解析规则：包含 {@code #} 时按 SpEL 求值（方法参数名可引用，如
 * {@code "#userId"}、{@code "'order:' + #orderId}"）；否则为字面量。</p>
 *
 * <p>被限流时抛出 {@link CoordRateLimitExceededException}（不静默吞掉调用）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface CoordRateLimit {

    /**
     * 限流键（含 {@code #} 时按 SpEL 求值）。
     */
    String key();

    /**
     * 每次调用申请的许可数。
     */
    int permits() default 1;
}
