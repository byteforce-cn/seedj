package cn.byteforce.seedj.web.core;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一 API 返回体（错误响应契约的载体，详见 seedj ADR-0002）。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@code code}：业务错误码，{@code 0} 表示成功（{@link ResponseCode#SUCCESS}），
 *       业务分支一律以 body 中的 {@code code} 为准；</li>
 *   <li>{@code message}：面向调用方的可读消息；</li>
 *   <li>{@code data}：业务数据；错误响应中恒为 {@code null}；</li>
 *   <li>{@code traceId}：链路追踪 ID（由运行时从 {@code RequestContext} 注入，可能为 {@code null}）。</li>
 * </ul>
 *
 * @param <T> 业务数据类型
 * @author ByteForce
 * @since 1.0.0
 */
public final class ApiResponse<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务错误码；{@code 0} 表示成功。
     */
    private final int code;

    /**
     * 面向调用方的可读消息。
     */
    private final String message;

    /**
     * 业务数据；错误响应中恒为 {@code null}。
     */
    private final T data;

    /**
     * 链路追踪 ID；可能为 {@code null}。
     */
    private final String traceId;

    /**
     * 构造返回体（无 traceId）。
     *
     * @param code    业务错误码
     * @param message 消息
     * @param data    业务数据
     */
    public ApiResponse(int code, String message, T data) {
        this(code, message, data, null);
    }

    /**
     * 构造返回体。
     *
     * @param code    业务错误码
     * @param message 消息
     * @param data    业务数据
     * @param traceId 链路追踪 ID；可为 {@code null}
     */
    public ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
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
     * 返回可读消息。
     *
     * @return 消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 返回业务数据。
     *
     * @return 业务数据；错误响应中为 {@code null}
     */
    public T getData() {
        return data;
    }

    /**
     * 返回链路追踪 ID。
     *
     * @return traceId；可能为 {@code null}
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * 成功返回体（无数据）。
     *
     * @param <T> 业务数据类型
     * @return 成功返回体
     */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /**
     * 成功返回体。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return 成功返回体
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ResponseCode.SUCCESS.getCode(),
                ResponseCode.SUCCESS.getMessage(), data, null);
    }

    /**
     * 错误返回体（消息取错误码默认消息）。
     *
     * @param errorCode 错误码
     * @param <T>       业务数据类型
     * @return 错误返回体
     */
    public static <T> ApiResponse<T> error(ResponseCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null, null);
    }

    /**
     * 错误返回体（自定义消息）。
     *
     * @param errorCode 错误码
     * @param message   自定义消息
     * @param <T>       业务数据类型
     * @return 错误返回体
     */
    public static <T> ApiResponse<T> error(ResponseCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, null);
    }

    /**
     * 错误返回体（自定义错误码与消息）。
     *
     * @param code    业务错误码
     * @param message 消息
     * @param <T>     业务数据类型
     * @return 错误返回体
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, null);
    }

    /**
     * 错误返回体（自定义错误码与消息，携带链路追踪 ID）。
     *
     * @param code    业务错误码
     * @param message 消息
     * @param traceId 链路追踪 ID；可为 {@code null}
     * @param <T>     业务数据类型
     * @return 错误返回体
     */
    public static <T> ApiResponse<T> error(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, traceId);
    }
}
