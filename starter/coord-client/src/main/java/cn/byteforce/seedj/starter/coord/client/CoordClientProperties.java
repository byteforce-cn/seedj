package cn.byteforce.seedj.starter.coord.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coord 客户端负载均衡 / 声明式 HTTP 配置属性。
 *
 * <p>前缀：{@code byteforce.coord.client}。语义见 {@code starter/coord/ROADMAP.md}
 * §0.2 约定 C6（Phase 0 定稿）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.coord.client")
public class CoordClientProperties {

    /**
     * 是否启用客户端负载均衡 / 声明式 HTTP 自动配置。
     */
    private boolean enabled = true;

    /**
     * 负载均衡配置。
     */
    private final LoadBalancer loadBalancer = new LoadBalancer();

    /**
     * 熔断状态观察桥：把 LB 调用成败上报 coord（Agent 本地状态，仅作协调 / 观测）。
     */
    private final CircuitBreakerReport circuitBreakerReport = new CircuitBreakerReport();

    /**
     * 获取是否启用。
     *
     * @return 是否启用客户端 LB / 声明式 HTTP 自动配置
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用客户端 LB / 声明式 HTTP 自动配置
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 获取负载均衡配置。
     *
     * @return 负载均衡配置
     */
    public LoadBalancer getLoadBalancer() { return loadBalancer; }

    /**
     * 获取熔断观察桥配置。
     *
     * @return 熔断状态观察桥配置
     */
    public CircuitBreakerReport getCircuitBreakerReport() { return circuitBreakerReport; }

    /**
     * 负载均衡配置。
     */
    public static class LoadBalancer {

        /**
         * 选择策略（{@code round-robin} 默认 / {@code random}）。
         */
        private Strategy strategy = Strategy.ROUND_ROBIN;

        /**
         * 获取选择策略。
         *
         * @return 选择策略
         */
        public Strategy getStrategy() { return strategy; }

        /**
         * 设置选择策略。
         *
         * @param strategy 选择策略
         */
        public void setStrategy(Strategy strategy) { this.strategy = strategy; }
    }

    /**
     * 选择策略枚举。
     */
    public enum Strategy {

        /** 轮询（默认）。 */
        ROUND_ROBIN("round-robin"),

        /** 随机。 */
        RANDOM("random");

        private final String value;

        Strategy(String value) {
            this.value = value;
        }

        /**
         * 配置字符串值（relaxed binding 目标形态）。
         *
         * @return 配置值
         */
        public String value() {
            return value;
        }
    }

    /**
     * 熔断状态观察桥配置（默认关闭；coord 熔断器为 Agent 本地状态，非客户端熔断器）。
     */
    public static class CircuitBreakerReport {

        /**
         * 是否启用调用成败上报（transport 级成功 / 失败）。
         */
        private boolean enabled = false;

        /**
         * 获取是否启用上报。
         *
         * @return 是否启用上报
         */
        public boolean isEnabled() { return enabled; }

        /**
         * 设置是否启用上报。
         *
         * @param enabled 是否启用上报
         */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
