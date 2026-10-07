package cn.byteforce.seedj.starter.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * seedj Web 运行时配置属性。
 *
 * <p>前缀：{@code byteforce.web}</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.web")
public class SeedjWebProperties {

    /**
     * 是否启用 seedj Web 运行时（全局异常处理、请求上下文过滤器），默认 {@code true}。
     */
    private boolean enabled = true;

    /**
     * 是否启用全局异常处理（统一错误响应契约），默认 {@code true}。
     */
    private boolean errorHandlingEnabled = true;

    /**
     * 是否启用请求上下文过滤器（traceId / 身份 / MDC），默认 {@code true}。
     */
    private boolean requestContextEnabled = true;

    /**
     * 链路追踪请求头名称（进入时读取、响应时回写），默认 {@code X-Trace-Id}。
     */
    private String traceIdHeader = "X-Trace-Id";

    /**
     * 是否在响应头回写链路追踪 ID，默认 {@code true}。
     */
    private boolean exposeTraceId = true;

    /**
     * 返回是否启用 seedj Web 运行时。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用 seedj Web 运行时。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回是否启用全局异常处理。
     *
     * @return 是否启用
     */
    public boolean isErrorHandlingEnabled() {
        return errorHandlingEnabled;
    }

    /**
     * 设置是否启用全局异常处理。
     *
     * @param errorHandlingEnabled 是否启用
     */
    public void setErrorHandlingEnabled(boolean errorHandlingEnabled) {
        this.errorHandlingEnabled = errorHandlingEnabled;
    }

    /**
     * 返回是否启用请求上下文过滤器。
     *
     * @return 是否启用
     */
    public boolean isRequestContextEnabled() {
        return requestContextEnabled;
    }

    /**
     * 设置是否启用请求上下文过滤器。
     *
     * @param requestContextEnabled 是否启用
     */
    public void setRequestContextEnabled(boolean requestContextEnabled) {
        this.requestContextEnabled = requestContextEnabled;
    }

    /**
     * 返回链路追踪请求头名称。
     *
     * @return 请求头名称
     */
    public String getTraceIdHeader() {
        return traceIdHeader;
    }

    /**
     * 设置链路追踪请求头名称。
     *
     * @param traceIdHeader 请求头名称
     */
    public void setTraceIdHeader(String traceIdHeader) {
        this.traceIdHeader = traceIdHeader;
    }

    /**
     * 返回是否在响应头回写链路追踪 ID。
     *
     * @return 是否回写
     */
    public boolean isExposeTraceId() {
        return exposeTraceId;
    }

    /**
     * 设置是否在响应头回写链路追踪 ID。
     *
     * @param exposeTraceId 是否回写
     */
    public void setExposeTraceId(boolean exposeTraceId) {
        this.exposeTraceId = exposeTraceId;
    }
}
