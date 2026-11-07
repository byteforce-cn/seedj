package cn.byteforce.seedj.core.security;

import java.util.Optional;

/**
 * 当前调用方的唯一入口。
 *
 * <p>业务代码注入本接口即可，不需要 import 任何 Spring Security 类型； 实现由 runtime starter 提供（令牌声明、请求上下文或异步作用域）。
 *
 * <p>只有一个方法：未认证是<b>正常状态</b>（公开端点、后台任务、事件消费）， 返回 {@link Optional#empty()}；"拒绝"由执行点（过滤器链 / 方法级安全）决定，
 * 不在本接口里表达。
 *
 * <p>测试替身：{@code CallerProvider} 是函数式接口，直接写 lambda 即可。
 *
 * <pre>{@code
 * CallerProvider provider = () -> Optional.of(caller);
 * }</pre>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface CallerProvider {

    /** 当前调用方；未认证返回 {@link Optional#empty()}，实现不得返回 {@code null}。 */
    Optional<Caller> find();
}
