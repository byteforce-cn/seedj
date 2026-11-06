package cn.byteforce.seedj.core.exception;

import cn.byteforce.seedj.core.CodeEnum;

/**
 * 操作契约的冻结错误码目录（线格式：固定前缀 + HTTP 状态码 + 三位序号）。
 *
 * <p>本枚举是唯一的「错误码字符串」来源：码表由规范冻结，新增属于 MINOR、
 * 修改既有含义属于 MAJOR。领域扩展使用自身前缀，不得占用冻结前缀。</p>
 *
 * <p>与仓库既有错误码枚举的关系：本枚举只负责线格式与 HTTP 语义，
 * 不替换 {@code CqrsErrorCode} / {@code IamErrorCode} / {@code SecurityErrorCode} /
 * {@code AuditErrorCode} / {@code InfrastructureErrorCode}；映射层在 Web 边界完成。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum ContractErrorCode implements CodeEnum<String> {

    /** 请求体不满足命令 Schema（结构/类型/格式错误）。 */
    INVALID_PAYLOAD("EIS-400-001", 400, false, "invalid payload"),

    /** 命令未通过业务规则校验。 */
    COMMAND_REJECTED("EIS-400-002", 400, false, "command rejected"),

    /** 违反时间契约（区间非法、系统时间轴被回改等）。 */
    TEMPORAL_VIOLATION("EIS-400-003", 400, false, "temporal violation"),

    /** 缺少或无效的认证凭证。 */
    UNAUTHENTICATED("EIS-401-001", 401, false, "unauthenticated"),

    /** 授权拒绝（reason 必填，来自权限判定）。 */
    FORBIDDEN("EIS-403-001", 403, false, "forbidden"),

    /** 调用方主体类型不在契约声明的 callers 内。 */
    CALLER_NOT_ALLOWED("EIS-403-002", 403, false, "caller not allowed"),

    /** 指定时点下业务对象不存在。 */
    OBJECT_NOT_FOUND("EIS-404-001", 404, false, "object not found"),

    /** 幂等冲突：同键重复提交内容不一致。 */
    IDEMPOTENCY_CONFLICT("EIS-409-001", 409, false, "idempotency conflict"),

    /** 乐观锁冲突：期望版本与当前版本不一致。 */
    VERSION_CONFLICT("EIS-409-002", 409, true, "version conflict"),

    /** 读模型未追平请求的 min_version（响应带 Retry-After）。 */
    READ_MODEL_NOT_READY("EIS-409-003", 409, true, "read model not ready"),

    /** 服务内部错误（响应不泄露内部细节，带 traceId）。 */
    INTERNAL_ERROR("EIS-500-001", 500, true, "internal error"),

    /** 依赖不可用（存储/事件通道），命令未被接受，可安全重试。 */
    UNAVAILABLE("EIS-503-001", 503, true, "unavailable");

    private final String code;

    private final int httpStatus;

    private final boolean retryable;

    private final String label;

    ContractErrorCode(String code, int httpStatus, boolean retryable, String label) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.label = label;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getLabel() {
        return label;
    }

    /**
     * 线格式对应的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 是否为可安全重试的错误。
     *
     * @return {@code true} 表示调用方可以退避重试
     */
    public boolean retryable() {
        return retryable;
    }
}
