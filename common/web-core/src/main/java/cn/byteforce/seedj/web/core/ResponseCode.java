package cn.byteforce.seedj.web.core;

import cn.byteforce.seedj.codegen.GenerateCodeEnum;
import cn.byteforce.seedj.core.CodeEnum;

/**
 * 统一错误码：业务失败码段为 {@code 1xxxx}，系统失败码段为 {@code 2xxxx}；
 * {@code 0} 为成功。
 *
 * <p>错误码是对外契约的一部分（seedj ADR-0002），新增只能追加、不得复用或改变既有语义；
 * 配套静态工厂由 codegen 生成（{@code ResponseCodeCodec}）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@GenerateCodeEnum
public enum ResponseCode implements CodeEnum<Integer> {

    /** 成功 */
    SUCCESS(0, "成功"),

    /** 通用失败 */
    FAILED(10000, "操作失败"),

    /** 请求参数错误 */
    BAD_REQUEST(10001, "请求参数错误"),

    /** 未认证 */
    UNAUTHORIZED(10002, "未认证或登录已过期"),

    /** 无权限 */
    FORBIDDEN(10003, "无权限访问"),

    /** 资源不存在 */
    NOT_FOUND(10004, "资源不存在"),

    /** 请求方法不支持 */
    METHOD_NOT_ALLOWED(10005, "请求方法不支持"),

    /** 请求媒体类型不支持 */
    UNSUPPORTED_MEDIA_TYPE(10009, "请求媒体类型不支持"),

    /** 请求过于频繁 */
    TOO_MANY_REQUESTS(10006, "请求过于频繁，请稍后重试"),

    /** 业务校验失败 */
    BIZ_ERROR(10007, "业务处理失败"),

    /** 数据冲突 */
    CONFLICT(10008, "数据冲突"),

    /** 系统内部错误 */
    INTERNAL_SERVER_ERROR(20000, "系统内部错误"),

    /** 依赖服务不可用 */
    SERVICE_UNAVAILABLE(20001, "依赖服务不可用"),

    /** 请求超时 */
    TIMEOUT(20002, "请求超时"),

    /** 未知错误 */
    UNKNOWN(29999, "未知错误");

    private final Integer code;
    private final String label;

    ResponseCode(Integer code, String message){
        this.code = code;
        this.label = message;
    }

    /**
     * 返回业务错误码。
     *
     * @return 业务错误码
     */
    @Override
    public Integer getCode() {
        return code;
    }

    /**
     * 返回错误码默认描述（label 即对外默认消息）。
     *
     * @return 描述文本
     */
    @Override
    public String getLabel() {
        return label;
    }

    /**
     * 返回错误码默认消息（与 {@link #getLabel()} 相同，语义化别名）。
     *
     * @return 默认消息
     */
    public String getMessage() {
        return label;
    }
}