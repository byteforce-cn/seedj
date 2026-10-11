package cn.byteforce.seedj.starter.eis.core;

/**
 * 命令终态（对齐 {@code 02-operation-contract/command.openapi.yaml} 的
 * {@code pending / accepted / rejected}）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum EisCommandStatus {

    /** 受理中（同事务内短驻；事务提交后不落此态）。 */
    PENDING("pending"),

    /** 已接受（事件已写入、审计已入链）。 */
    ACCEPTED("accepted"),

    /** 已拒绝（规则/处理器拒绝；不入事件与审计链）。 */
    REJECTED("rejected");

    private final String code;

    EisCommandStatus(String code) {
        this.code = code;
    }

    /**
     * 获取契约字面量。
     *
     * @return 小写字面量
     */
    public String getCode() {
        return code;
    }
}
