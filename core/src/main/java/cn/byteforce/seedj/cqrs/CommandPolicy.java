package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;

import java.util.Map;

/**
 * 命令级授权策略：在目录执行命令之前决定“这个调用方能否执行这条命令”。
 *
 * <p>默认实现 {@link #permissions()} 以 {@link CommandDescriptor#permission()} 为准；权限是
 * 命令契约的一部分，不是 controller 的局部判断。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface CommandPolicy {

    /**
     * 授权一次命令执行。
     *
     * @param descriptor 命令描述
     * @param caller     调用方
     * @param payload    原始载荷（供 ABAC 策略使用）
     * @throws ContractException 无权限（403）时抛出
     */
    void authorize(CommandDescriptor descriptor, Caller caller, Map<String, Object> payload);

    /**
     * 基于权限名的默认策略。
     *
     * <p>描述缺少权限时抛 {@link IllegalArgumentException}：这是装配错误，应在启动期暴露，
     * 而不是让一条命令变成“人人可执行”。</p>
     *
     * @return 权限策略
     */
    static CommandPolicy permissions() {
        return (descriptor, caller, payload) -> {
            String permission = descriptor.permission();
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException(
                        "command " + descriptor.type() + " has no permission declared");
            }
            if (caller == null || !caller.hasPermission(permission)) {
                throw new ContractException(ContractErrorCode.FORBIDDEN,
                        "missing permission: " + permission);
            }
        };
    }

    /**
     * 不做授权检查；仅供测试与显式本地装配。
     *
     * @return 放行策略
     */
    static CommandPolicy allowAll() {
        return (descriptor, caller, payload) -> {};
    }
}
