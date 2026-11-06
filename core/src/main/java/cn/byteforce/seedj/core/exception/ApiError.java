package cn.byteforce.seedj.core.exception;

import java.util.Map;
import java.util.Objects;

/**
 * 错误响应信封：所有错误响应必须包含 {@code code} 与 {@code message}，
 * {@code details} 可选。
 *
 * @param code    冻结错误码（见 {@link ContractErrorCode}）
 * @param message 面向调用方的消息
 * @param details 可选细节
 * @author ByteForce
 * @since 1.0.0
 */
public record ApiError(String code, String message, Map<String, Object> details) {

    public ApiError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * 从契约错误构造信封。
     *
     * @param exception 契约错误
     * @return 错误信封
     */
    public static ApiError of(ContractException exception) {
        return new ApiError(exception.code().getCode(), exception.reason(), exception.details());
    }
}
