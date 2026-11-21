package cn.byteforce.seedj.observability;

/**
 * 可观测的能力 provider。
 *
 * <p>{@link #health()} 必须是非破坏性的只读探针：不得抛异常、不得改变状态、不得触发签发/写账本。 无法判定时返回 {@link
 * HealthStatus#unknown(String)}，不要假装健康。
 */
public interface ObservableProvider {

    HealthStatus health();
}
