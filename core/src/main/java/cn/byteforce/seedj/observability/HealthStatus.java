package cn.byteforce.seedj.observability;

/**
 * 能力 provider 的健康状态。
 *
 * <p>契约：{@code health()} 不得抛异常；无法判定时返回 {@link #unknown(String)}。健康探针只回答「这个 provider
 * 现在能不能用」，不做重试、不缓存失败结论；调用方负责节奏。
 */
public sealed interface HealthStatus
        permits HealthStatus.Up, HealthStatus.Down, HealthStatus.Unknown {

    /** 正常。{@code detail} 是可选的诊断信息，不含敏感数据。 */
    record Up(String detail) implements HealthStatus {}

    /** 不可用。{@code cause} 可为 {@code null}。 */
    record Down(String reason, Throwable cause) implements HealthStatus {}

    /** 无法判定（依赖未配置、探针被跳过、provider 已关闭等）。 */
    record Unknown(String reason) implements HealthStatus {}

    static HealthStatus up() {
        return new Up("ok");
    }

    static HealthStatus up(String detail) {
        return new Up(detail);
    }

    static HealthStatus down(String reason) {
        return new Down(reason, null);
    }

    static HealthStatus down(String reason, Throwable cause) {
        return new Down(reason, cause);
    }

    static HealthStatus unknown(String reason) {
        return new Unknown(reason);
    }

    /** 只有 {@link Up} 算健康；{@link Unknown} 不能当作健康放行。 */
    default boolean healthy() {
        return this instanceof Up;
    }
}
