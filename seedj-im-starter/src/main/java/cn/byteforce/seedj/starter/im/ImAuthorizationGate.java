package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.security.Caller;

/**
 * 单聊授权闸门：A 能否给 B 发消息。
 *
 * <p>好友、黑名单、组织关系都由宿主实现；默认实现只做「非空且不是自己」，租户隔离由服务层统一 校验（不依赖这个接口）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface ImAuthorizationGate {

    /**
     * 是否允许发起或继续单聊。
     *
     * @param caller 调用方
     * @param peerId 对端 subject
     * @return 允许为 {@code true}
     */
    boolean canMessage(Caller caller, String peerId);
}
