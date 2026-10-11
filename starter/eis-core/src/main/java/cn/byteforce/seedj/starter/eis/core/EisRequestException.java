package cn.byteforce.seedj.starter.eis.core;

/**
 * EIS 请求级异常：命令传输面的错误（错误码目录 {@code 02-operation-contract/error-codes.yaml}）。
 *
 * <p>与 {@link EisCommandRejectedException} 的区别：本异常发生在命令"受理之前"
 * （报文缺失/非法字段、未认证、命令记录不存在等），没有可持久化的命令记录；
 * 拒绝异常发生在受理之后的规则/处理器阶段，命令记录以 rejected 终态落库。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisRequestException extends RuntimeException {

    private final String code;

    private final int httpStatus;

    /**
     * 构造请求异常。
     *
     * @param code       EIS 错误码
     * @param httpStatus 对应 HTTP 状态码
     * @param message    描述
     */
    public EisRequestException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * 请求体不满足命令 Schema（EIS-400-001）。
     *
     * @param message 描述
     * @return 异常实例
     */
    public static EisRequestException invalidPayload(String message) {
        return new EisRequestException("EIS-400-001", 400, message);
    }

    /**
     * 缺少或无效认证凭证（EIS-401-001）。
     *
     * @return 异常实例
     */
    public static EisRequestException unauthenticated() {
        return new EisRequestException("EIS-401-001", 401, "缺少或无效的认证凭证");
    }

    /**
     * 指定时点下业务对象不存在（EIS-404-001）。
     *
     * @param message 描述
     * @return 异常实例
     */
    public static EisRequestException notFound(String message) {
        return new EisRequestException("EIS-404-001", 404, message);
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
