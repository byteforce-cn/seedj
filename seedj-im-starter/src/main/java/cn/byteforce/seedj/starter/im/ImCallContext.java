package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.security.Caller;
import java.util.Objects;

/**
 * 一次 IM 命令的上下文：身份、设备、序号与回写通道。
 *
 * <p>刻意不暴露 Netty 的 {@code Channel}：处理器只描述「回什么」，不关心帧怎么发、连接还在不在。 主动通知（例如把消息推给同会话其它端）走 {@link
 * #notify(byte, Object)}。
 *
 * @param caller 已握手的主体
 * @param deviceId 设备标识；客户端未提供时为连接级生成的 id
 * @param sequenceId 请求序号，响应原样带回
 * @param notifier 主动通知通道
 * @author ByteForce
 * @since 1.0.0
 */
public record ImCallContext(Caller caller, String deviceId, int sequenceId, Notifier notifier) {

    public ImCallContext {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(notifier, "notifier");
        deviceId = deviceId == null || deviceId.isBlank() ? "unknown" : deviceId;
    }

    /**
     * 向当前连接发一条通知（{@code msgType=2}，序号为 0）。
     *
     * @param cmd 通知命令字
     * @param body 通知体
     */
    public void notify(byte cmd, Object body) {
        notifier.send(cmd, body);
    }

    /** 通知通道：由传输层实现，处理器不依赖 Netty。 */
    @FunctionalInterface
    public interface Notifier {

        /**
         * 发送一条通知帧。
         *
         * @param cmd 命令字
         * @param body 帧体
         */
        void send(byte cmd, Object body);
    }
}
