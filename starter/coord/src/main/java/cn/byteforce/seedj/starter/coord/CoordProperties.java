package cn.byteforce.seedj.starter.coord;

import cn.byteforce.coord.sdk.CoordConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Coord 客户端配置属性。
 *
 * <p>前缀：{@code byteforce.coord}</p>
 *
 * <p>默认值与 {@code coord-java-sdk} 的 {@link CoordConfig.Builder} 保持一致。
 * 最终配置由 SDK 做 fail-closed 校验（非 loopback 明文拒绝、TLS 缺证书拒绝），
 * 配置错误会直接导致应用启动失败而不是静默降级。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.coord")
public class CoordProperties {

    /**
     * 是否启用 Coord 自动配置。
     */
    private boolean enabled = true;

    /**
     * Coord Agent 主机。
     *
     * <p>coord 的部署模型是「每台机器一个 Agent」，默认连本机。</p>
     */
    private String agentHost = "127.0.0.1";

    /**
     * Coord Agent gRPC 端口。
     */
    private int agentPort = 19527;

    /**
     * 单次 RPC 超时（连接协商、健康探测与所有业务调用）。
     */
    private Duration requestTimeout = Duration.ofSeconds(5);

    /**
     * 断线后是否自动恢复 Watch 订阅。
     */
    private boolean autoRestoreWatches = true;

    /**
     * 心跳/重连调度线程数。
     */
    private int heartbeatThreads = 4;

    /**
     * 是否允许对非 loopback 地址使用明文通道。
     *
     * <p>默认拒绝（fail-closed）；仅在可信网络显式放开。</p>
     */
    private boolean allowInsecurePlaintext = false;

    /**
     * 固定 CCT 凭据（本地开发 / 短期凭据）。
     *
     * <p>长期运行请提供 {@link CoordAuthTokenSupplier} Bean：每次 RPC 读取当前 token，
     * 凭据轮换无需重建 channel；本属性仅在无 Supplier Bean 时生效。</p>
     */
    private String authToken;

    /**
     * 启动阶段等待 Agent 可达并完成协议协商的超时。
     *
     * <p>为空或 0 时跳过（首次调用时惰性协商）；显式设置后协商失败会阻止应用启动（fail-fast）。</p>
     */
    private Duration awaitConnectedTimeout;

    /**
     * TLS 配置。
     */
    private final Tls tls = new Tls();

    /**
     * 将当前属性转换为 SDK 的 {@link CoordConfig.Builder}。
     *
     * <p>需要从容器解析的可选项（凭据 Supplier、ObservabilityProvider）由自动配置补充。</p>
     */
    public CoordConfig.Builder toBuilder() {
        CoordConfig.Builder builder = CoordConfig.builder()
                .agentHost(agentHost)
                .agentPort(agentPort)
                .requestTimeout(requestTimeout)
                .autoRestoreWatches(autoRestoreWatches)
                .heartbeatThreads(heartbeatThreads);
        if (tls.isEnabled()) {
            builder.useTls(true)
                    .tlsCaCertPath(tls.getCaCertPath())
                    .tlsClientCertPath(tls.getClientCertPath())
                    .tlsClientKeyPath(tls.getClientKeyPath());
        }
        if (allowInsecurePlaintext) {
            builder.allowInsecurePlaintext(true);
        }
        return builder;
    }

    /**
     * 获取是否启用。
     *
     * @return 是否启用 Coord 自动配置
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用 Coord 自动配置
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 获取 Agent 主机。
     *
     * @return Agent 主机
     */
    public String getAgentHost() { return agentHost; }

    /**
     * 设置 Agent 主机。
     *
     * @param agentHost Agent 主机
     */
    public void setAgentHost(String agentHost) { this.agentHost = agentHost; }

    /**
     * 获取 Agent 端口。
     *
     * @return Agent gRPC 端口
     */
    public int getAgentPort() { return agentPort; }

    /**
     * 设置 Agent 端口。
     *
     * @param agentPort Agent gRPC 端口
     */
    public void setAgentPort(int agentPort) { this.agentPort = agentPort; }

    /**
     * 获取 RPC 超时。
     *
     * @return 单次 RPC 超时
     */
    public Duration getRequestTimeout() { return requestTimeout; }

    /**
     * 设置 RPC 超时。
     *
     * @param requestTimeout 单次 RPC 超时
     */
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }

    /**
     * 获取是否自动恢复 Watch。
     *
     * @return 断线后是否自动恢复 Watch
     */
    public boolean isAutoRestoreWatches() { return autoRestoreWatches; }

    /**
     * 设置是否自动恢复 Watch。
     *
     * @param autoRestoreWatches 断线后是否自动恢复 Watch
     */
    public void setAutoRestoreWatches(boolean autoRestoreWatches) { this.autoRestoreWatches = autoRestoreWatches; }

    /**
     * 获取心跳线程数。
     *
     * @return 心跳/重连调度线程数
     */
    public int getHeartbeatThreads() { return heartbeatThreads; }

    /**
     * 设置心跳线程数。
     *
     * @param heartbeatThreads 心跳/重连调度线程数
     */
    public void setHeartbeatThreads(int heartbeatThreads) { this.heartbeatThreads = heartbeatThreads; }

    /**
     * 获取是否允许明文。
     *
     * @return 是否允许非 loopback 明文
     */
    public boolean isAllowInsecurePlaintext() { return allowInsecurePlaintext; }

    /**
     * 设置是否允许明文。
     *
     * @param allowInsecurePlaintext 是否允许非 loopback 明文
     */
    public void setAllowInsecurePlaintext(boolean allowInsecurePlaintext) { this.allowInsecurePlaintext = allowInsecurePlaintext; }

    /**
     * 获取固定凭据。
     *
     * @return 固定 CCT 凭据
     */
    public String getAuthToken() { return authToken; }

    /**
     * 设置固定凭据。
     *
     * @param authToken 固定 CCT 凭据
     */
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    /**
     * 获取启动等待超时。
     *
     * @return 启动期等待 Agent 可达的超时
     */
    public Duration getAwaitConnectedTimeout() { return awaitConnectedTimeout; }

    /**
     * 设置启动等待超时。
     *
     * @param awaitConnectedTimeout 启动期等待 Agent 可达的超时
     */
    public void setAwaitConnectedTimeout(Duration awaitConnectedTimeout) { this.awaitConnectedTimeout = awaitConnectedTimeout; }

    /**
     * 获取 TLS 配置。
     *
     * @return TLS 配置
     */
    public Tls getTls() { return tls; }

    /**
     * TLS / mTLS 配置。
     */
    public static class Tls {

        /**
         * 是否启用 TLS（连接非 loopback Agent 必须启用，或显式允许明文）。
         */
        private boolean enabled = false;

        /**
         * CA 证书路径（启用 TLS 时必填，缺失即拒绝启动）。
         */
        private String caCertPath;

        /**
         * 客户端证书路径（与私钥成对提供即为 mTLS）。
         */
        private String clientCertPath;

        /**
         * 客户端私钥路径（与客户端证书成对提供即为 mTLS）。
         */
        private String clientKeyPath;

        /**
         * 获取是否启用。
         *
         * @return 是否启用 TLS
         */
        public boolean isEnabled() { return enabled; }

        /**
         * 设置是否启用。
         *
         * @param enabled 是否启用 TLS
         */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        /**
         * 获取 CA 证书路径。
         *
         * @return CA 证书路径
         */
        public String getCaCertPath() { return caCertPath; }

        /**
         * 设置 CA 证书路径。
         *
         * @param caCertPath CA 证书路径
         */
        public void setCaCertPath(String caCertPath) { this.caCertPath = caCertPath; }

        /**
         * 获取客户端证书路径。
         *
         * @return 客户端证书路径
         */
        public String getClientCertPath() { return clientCertPath; }

        /**
         * 设置客户端证书路径。
         *
         * @param clientCertPath 客户端证书路径
         */
        public void setClientCertPath(String clientCertPath) { this.clientCertPath = clientCertPath; }

        /**
         * 获取客户端私钥路径。
         *
         * @return 客户端私钥路径
         */
        public String getClientKeyPath() { return clientKeyPath; }

        /**
         * 设置客户端私钥路径。
         *
         * @param clientKeyPath 客户端私钥路径
         */
        public void setClientKeyPath(String clientKeyPath) { this.clientKeyPath = clientKeyPath; }
    }
}
