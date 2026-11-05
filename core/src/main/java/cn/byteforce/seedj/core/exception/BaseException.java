package cn.byteforce.seedj.core.exception;

import java.io.Serial;

/**
 * seedj 异常体系基类。
 *
 * <p>错误响应契约（seedj ADR-0002）以本体系为承载：</p>
 * <ul>
 *   <li>{@link #getCode()} 是对外契约的一部分，错误响应体中的 {@code code} 直接取自此处；</li>
 *   <li>{@link #getHttpStatus()} 是 Web 边界期望的 HTTP 状态码，默认
 *       {@link #DEFAULT_HTTP_STATUS}（业务失败以响应体 code 为准，HTTP 状态仅表达基础设施语义）；</li>
 *   <li>异常消息面向调用方与运维，不包含堆栈与内部细节；内部排障信息走日志。</li>
 * </ul>
 *
 * <p>业务代码优先使用 {@link BizException}；依赖调用失败使用 {@link RemoteException}；
 * 需要自定义 HTTP 语义时可直接继承本类。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class BaseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 默认 HTTP 状态码：业务失败以响应体 code 为准。
     */
    public static final int DEFAULT_HTTP_STATUS = 200;

    /**
     * 业务错误码。
     */
    private final int code;

    /**
     * Web 边界期望的 HTTP 状态码。
     */
    private final int httpStatus;

    /**
     * 构造异常（HTTP 状态码取默认值）。
     *
     * @param code    业务错误码
     * @param message 错误消息
     */
    public BaseException(int code, String message) {
        this(code, message, DEFAULT_HTTP_STATUS, null);
    }

    /**
     * 构造异常（HTTP 状态码取默认值）。
     *
     * @param code    业务错误码
     * @param message 错误消息
     * @param cause   根因
     */
    public BaseException(int code, String message, Throwable cause) {
        this(code, message, DEFAULT_HTTP_STATUS, cause);
    }

    /**
     * 构造异常。
     *
     * @param code       业务错误码
     * @param message    错误消息
     * @param httpStatus Web 边界期望的 HTTP 状态码
     */
    public BaseException(int code, String message, int httpStatus) {
        this(code, message, httpStatus, null);
    }

    /**
     * 构造异常。
     *
     * @param code       业务错误码
     * @param message    错误消息
     * @param httpStatus Web 边界期望的 HTTP 状态码
     * @param cause      根因
     */
    public BaseException(int code, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * 返回业务错误码。
     *
     * @return 业务错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * 返回 Web 边界期望的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
