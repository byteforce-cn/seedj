package cn.byteforce.seedj.cqrs;

/**
 * 命令载荷校验 seam。
 *
 * <p>校验在锁与事务之前执行（{@link CommandRuntime} 的顺序契约）；实现应把失败表达为
 * {@link cn.byteforce.seedj.core.exception.ContractException}（无效载荷），不要抛技术异常。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface CommandValidator {

    /**
     * 校验类型化命令。
     *
     * @param command 命令载荷
     */
    void validate(Object command);

    /**
     * 不做校验。
     *
     * @return 空校验器
     */
    static CommandValidator none() {
        return command -> {};
    }
}
