package cn.byteforce.seedj.core;

/**
 * 一个可编码的错误项。由各能力的错误码枚举实现。
 *
 * <p>契约实现方可以只实现本接口，而不必继承 {@link InfrastructureException}。
 */
public interface ErrorDescriptor {

    /** 稳定的错误码名称，写入日志与响应体，不得随重构变化。 */
    String name();

    /** 错误分类。 */
    ErrorCategory category();

    /** 该错误默认是否可重试。 */
    boolean defaultRetryable();
}
