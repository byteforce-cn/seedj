package cn.byteforce.seedj.codegen;

/**
 * 默认打码规则的建期引用；生成器据此生成 {@code Maskers.xxx()} 调用。
 *
 * <p>注解不能携带实例，所以这里只登记工厂名；运行时实现仍是 {@link cn.byteforce.seedj.domain.Maskers}，不复制第二套打码逻辑。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum MaskerName {

    /** 不打码。 */
    NONE,

    /** 保留末尾四位；见 {@link cn.byteforce.seedj.domain.Maskers#last4()}。 */
    LAST4,

    /** 邮箱局部与域名各保留首字符；见 {@link cn.byteforce.seedj.domain.Maskers#email()}。 */
    EMAIL
}
