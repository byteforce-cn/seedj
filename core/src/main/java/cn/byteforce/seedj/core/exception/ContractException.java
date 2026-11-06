package cn.byteforce.seedj.core.exception;

import java.util.Map;
import java.util.Objects;

/**
 * 契约错误：携带冻结错误码、面向调用方的原因与可选细节。
 *
 * <p>与既有 {@link BaseException} 体系并列而不是替换：本类只用于契约面
 * （命令/查询/授权），由 Web 边界转换成 {@link ApiError} 信封。
 * 既有基础设施异常仍走 {@code InfrastructureException} / {@code BizException}。</p>
 *
 * <p>{@code details} 不得包含内部堆栈或敏感字段全文；{@code reasonCode}
 * 用于授权等场景的细分原因（如 SoD 规则号），可为空。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class ContractException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ContractErrorCode code;

    private final String reason;

    private final Map<String, Object> details;

    private final String reasonCode;

    /**
     * 构造错误（无细节、无细分原因）。
     *
     * @param code   冻结错误码
     * @param reason 面向调用方的原因
     */
    public ContractException(ContractErrorCode code, String reason) {
        this(code, reason, Map.of(), null);
    }

    /**
     * 构造错误。
     *
     * @param code       冻结错误码
     * @param reason     面向调用方的原因
     * @param details    可选细节（不得含敏感值全文）
     * @param reasonCode 可选细分原因（如 {@code SOD-001}）
     */
    public ContractException(ContractErrorCode code, String reason,
                             Map<String, Object> details, String reasonCode) {
        super(Objects.requireNonNull(reason, "reason"));
        this.code = Objects.requireNonNull(code, "code");
        this.reason = reason;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.reasonCode = reasonCode;
    }

    /**
     * 冻结错误码。
     *
     * @return 错误码
     */
    public ContractErrorCode code() {
        return code;
    }

    /**
     * 面向调用方的原因（与 {@link #getMessage()} 相同）。
     *
     * @return 原因文本
     */
    public String reason() {
        return reason;
    }

    /**
     * 可选细节。
     *
     * @return 不可变细节映射
     */
    public Map<String, Object> details() {
        return details;
    }

    /**
     * 可选细分原因。
     *
     * @return 细分原因，可为 {@code null}
     */
    public String reasonCode() {
        return reasonCode;
    }
}
