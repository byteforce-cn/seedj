package cn.byteforce.seedj.core.exception;

import cn.byteforce.seedj.core.CodeEnum;

import java.io.Serial;
import java.util.Objects;

/**
 * 远程调用异常：依赖服务不可用、超时、协议错误等。
 *
 * <p>默认 HTTP 状态码为 {@link #DEFAULT_HTTP_STATUS}（{@code 503}，依赖服务不可用）；
 * 需要表达网关超时等语义时使用 {@code RemoteException(code, message, httpStatus, cause)} 形式。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class RemoteException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 默认 HTTP 状态码：依赖服务不可用（{@code 503}）。
     */
    public static final int DEFAULT_HTTP_STATUS = 503;

    /**
     * 构造异常（消息取错误码的 label）。
     *
     * @param code 错误码枚举
     */
    public RemoteException(CodeEnum<Integer> code) {
        this(code, (String) null);
    }

    /**
     * 构造异常（消息为空时回落到错误码的 label）。
     *
     * @param code    错误码枚举
     * @param message 自定义错误消息；为 {@code null} 时使用错误码的 label
     */
    public RemoteException(CodeEnum<Integer> code, String message) {
        super(requireCode(code), resolveMessage(code, message), DEFAULT_HTTP_STATUS);
    }

    /**
     * 构造异常（消息取错误码的 label）。
     *
     * @param code  错误码枚举
     * @param cause 根因
     */
    public RemoteException(CodeEnum<Integer> code, Throwable cause) {
        super(requireCode(code), resolveMessage(code, null), DEFAULT_HTTP_STATUS, cause);
    }

    /**
     * 构造异常（消息为空时回落到错误码的 label）。
     *
     * @param code    错误码枚举
     * @param message 自定义错误消息；为 {@code null} 时使用错误码的 label
     * @param cause   根因
     */
    public RemoteException(CodeEnum<Integer> code, String message, Throwable cause) {
        super(requireCode(code), resolveMessage(code, message), DEFAULT_HTTP_STATUS, cause);
    }

    /**
     * 构造异常。
     *
     * @param code       业务错误码
     * @param message    错误消息
     * @param httpStatus Web 边界期望的 HTTP 状态码
     * @param cause      根因
     */
    public RemoteException(int code, String message, int httpStatus, Throwable cause) {
        super(code, message, httpStatus, cause);
    }

    private static int requireCode(CodeEnum<Integer> code) {
        return Objects.requireNonNull(code, "code 不能为 null").getCode();
    }

    private static String resolveMessage(CodeEnum<Integer> code, String message) {
        return (message != null) ? message : code.getLabel();
    }
}
