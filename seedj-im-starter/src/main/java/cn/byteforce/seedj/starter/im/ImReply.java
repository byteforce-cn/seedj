package cn.byteforce.seedj.starter.im;

/**
 * 一次 IM 命令的响应。
 *
 * <p>处理器返回 {@code null} 表示「不回包」（例如 ACK）。
 *
 * @param cmd 响应命令字
 * @param body 响应体；可为 {@code null}
 * @author ByteForce
 * @since 1.0.0
 */
public record ImReply(byte cmd, Object body) {

    /**
     * 构造一个回包。
     *
     * @param cmd 响应命令字
     * @param body 响应体
     * @return 响应
     */
    public static ImReply of(byte cmd, Object body) {
        return new ImReply(cmd, body);
    }
}
