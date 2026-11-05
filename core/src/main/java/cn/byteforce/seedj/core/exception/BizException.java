package cn.byteforce.seedj.core.exception;

import cn.byteforce.seedj.core.CodeEnum;

import java.io.Serial;
import java.util.Objects;

/**
 * 业务异常：可预期的业务规则失败（参数、状态、权限等业务语义）。
 *
 * <p>默认 HTTP 状态码为 {@link #DEFAULT_HTTP_STATUS}（业务失败以响应体 code 为准）；
 * code 建议取自统一错误码枚举（如 {@code ResponseCode}），可直接以枚举构造：</p>
 *
 * <pre>{@code
 * throw new BizException(ResponseCode.BIZ_ERROR);
 * throw new BizException(ResponseCode.BIZ_ERROR, "库存不足");
 * }</pre>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class BizException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 构造异常（消息取错误码的 label）。
     *
     * @param code 错误码枚举
     */
    public BizException(CodeEnum<Integer> code) {
        this(code, (String) null);
    }

    /**
     * 构造异常（消息为空时回落到错误码的 label）。
     *
     * @param code    错误码枚举
     * @param message 自定义错误消息；为 {@code null} 时使用错误码的 label
     */
    public BizException(CodeEnum<Integer> code, String message) {
        super(requireCode(code), resolveMessage(code, message), DEFAULT_HTTP_STATUS);
    }

    /**
     * 构造异常（消息取错误码的 label）。
     *
     * @param code  错误码枚举
     * @param cause 根因
     */
    public BizException(CodeEnum<Integer> code, Throwable cause) {
        super(requireCode(code), resolveMessage(code, null), DEFAULT_HTTP_STATUS, cause);
    }

    /**
     * 构造异常（消息为空时回落到错误码的 label）。
     *
     * @param code    错误码枚举
     * @param message 自定义错误消息；为 {@code null} 时使用错误码的 label
     * @param cause   根因
     */
    public BizException(CodeEnum<Integer> code, String message, Throwable cause) {
        super(requireCode(code), resolveMessage(code, message), DEFAULT_HTTP_STATUS, cause);
    }

    /**
     * 构造异常。
     *
     * @param code    业务错误码
     * @param message 错误消息
     */
    public BizException(int code, String message) {
        super(code, message, DEFAULT_HTTP_STATUS);
    }

    /**
     * 构造异常。
     *
     * @param code    业务错误码
     * @param message 错误消息
     * @param cause   根因
     */
    public BizException(int code, String message, Throwable cause) {
        super(code, message, DEFAULT_HTTP_STATUS, cause);
    }

    private static int requireCode(CodeEnum<Integer> code) {
        return Objects.requireNonNull(code, "code 不能为 null").getCode();
    }

    private static String resolveMessage(CodeEnum<Integer> code, String message) {
        return (message != null) ? message : code.getLabel();
    }
}
