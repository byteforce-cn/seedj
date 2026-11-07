package cn.byteforce.seedj.cqrs;

import java.util.Locale;

/**
 * 命令记录的状态。
 *
 * <p>线格式使用小写名（{@code pending} / {@code accepted} / {@code rejected}）， 数据库与 HTTP 响应都使用线格式；Java
 * 枚举名只用于代码内比较。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum CommandStatus {

    /** 已受理，尚未得到业务终态。 */
    PENDING,

    /** 已接受并产生对象版本。 */
    ACCEPTED,

    /** 被业务规则拒绝。 */
    REJECTED;

    /**
     * 线格式名称。
     *
     * @return 小写状态名
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析线格式名称。
     *
     * @param value 小写状态名
     * @return 状态
     * @throws IllegalArgumentException 不是三个冻结值之一
     */
    public static CommandStatus fromWire(String value) {
        if (value != null) {
            switch (value) {
                case "pending" -> {
                    return PENDING;
                }
                case "accepted" -> {
                    return ACCEPTED;
                }
                case "rejected" -> {
                    return REJECTED;
                }
                default -> {
                    // fall through to the shared error below
                }
            }
        }
        throw new IllegalArgumentException("unknown command status: " + value);
    }
}
