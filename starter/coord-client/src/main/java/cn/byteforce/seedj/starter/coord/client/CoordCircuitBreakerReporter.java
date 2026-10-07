package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.coord.sdk.circuitbreaker.CircuitBreakerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 熔断状态观察桥（C6 / Phase 3）：把调用成败上报 coord（Agent 本地熔断状态，
 * **仅作状态协调与观测**，非客户端熔断器；客户端弹性由应用侧（如 Resilience4j）组合）。
 *
 * <p>可空注入：未启用时传 {@code null}，调用方自行判空（本类不上报任何默认语义）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordCircuitBreakerReporter {

    private static final Logger log = LoggerFactory.getLogger(CoordCircuitBreakerReporter.class);

    private final CircuitBreakerClient circuitBreakerClient;

    /**
     * @param circuitBreakerClient SDK 熔断客户端（Agent 本地状态）
     */
    public CoordCircuitBreakerReporter(CircuitBreakerClient circuitBreakerClient) {
        this.circuitBreakerClient = circuitBreakerClient;
    }

    /**
     * 上报成功（transport 级）。
     *
     * @param name 熔断键（本模块以服务名为键）
     */
    public void reportSuccess(String name) {
        try {
            circuitBreakerClient.reportSuccess(name);
        } catch (RuntimeException e) {
            log.debug("熔断状态上报失败（已忽略）: name={}, 原因={}", name, e.getMessage());
        }
    }

    /**
     * 上报失败（transport 级）。
     *
     * @param name 熔断键（本模块以服务名为键）
     */
    public void reportFailure(String name) {
        try {
            circuitBreakerClient.reportFailure(name);
        } catch (RuntimeException e) {
            log.debug("熔断状态上报失败（已忽略）: name={}, 原因={}", name, e.getMessage());
        }
    }
}
