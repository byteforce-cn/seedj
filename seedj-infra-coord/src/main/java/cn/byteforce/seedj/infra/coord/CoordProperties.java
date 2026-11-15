package cn.byteforce.seedj.infra.coord;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * coord 连接与能力配置。
 *
 * <p>连接配置只在 {@code seedj.coord.enabled=true} 时生效；各能力通过 {@code seedj.<cap>.provider=coord}
 * 选择，不使用本地实现兜底。
 */
@ConfigurationProperties(prefix = "seedj.coord")
public class CoordProperties {

    /** coord 总开关；关闭时不会创建 CoordClient，也不会装配任何 coord provider。 */
    private boolean enabled;

    /** coord-agent gRPC 地址。 */
    private String agentHost = "127.0.0.1";

    /** coord-agent gRPC 端口。 */
    private int agentPort = 19527;

    /** 单次 RPC 超时。 */
    private Duration requestTimeout = Duration.ofSeconds(5);

    /** 断线后是否自动恢复 watch。 */
    private boolean autoRestoreWatches = true;

    /** SDK 心跳线程数。 */
    private int heartbeatThreads = 4;

    /** 非 loopback 明文通道的显式逃生口；默认 fail-closed。 */
    private boolean allowInsecurePlaintext;

    /** ID/编号/锁/工作流的命名空间前缀；空 = {@code seedj:<spring.application.name>}。 */
    private String namespace;

    private final Tls tls = new Tls();
    private final Auth auth = new Auth();
    private final Id id = new Id();
    private final Numbering numbering = new Numbering();
    private final Clock clock = new Clock();
    private final EventBus eventbus = new EventBus();
    private final Lock lock = new Lock();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAgentHost() {
        return agentHost;
    }

    public void setAgentHost(String agentHost) {
        this.agentHost = agentHost;
    }

    public int getAgentPort() {
        return agentPort;
    }

    public void setAgentPort(int agentPort) {
        this.agentPort = agentPort;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public boolean isAutoRestoreWatches() {
        return autoRestoreWatches;
    }

    public void setAutoRestoreWatches(boolean autoRestoreWatches) {
        this.autoRestoreWatches = autoRestoreWatches;
    }

    public int getHeartbeatThreads() {
        return heartbeatThreads;
    }

    public void setHeartbeatThreads(int heartbeatThreads) {
        this.heartbeatThreads = heartbeatThreads;
    }

    public boolean isAllowInsecurePlaintext() {
        return allowInsecurePlaintext;
    }

    public void setAllowInsecurePlaintext(boolean allowInsecurePlaintext) {
        this.allowInsecurePlaintext = allowInsecurePlaintext;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Tls getTls() {
        return tls;
    }

    public Auth getAuth() {
        return auth;
    }

    public Id getId() {
        return id;
    }

    public Numbering getNumbering() {
        return numbering;
    }

    public Clock getClock() {
        return clock;
    }

    public EventBus getEventbus() {
        return eventbus;
    }

    public Lock getLock() {
        return lock;
    }

    /** TLS 配置；启用时 CA/客户端证书路径由 SDK fail-closed 校验。 */
    public static class Tls {
        private boolean enabled;
        private String caCertPath;
        private String clientCertPath;
        private String clientKeyPath;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCaCertPath() {
            return caCertPath;
        }

        public void setCaCertPath(String caCertPath) {
            this.caCertPath = caCertPath;
        }

        public String getClientCertPath() {
            return clientCertPath;
        }

        public void setClientCertPath(String clientCertPath) {
            this.clientCertPath = clientCertPath;
        }

        public String getClientKeyPath() {
            return clientKeyPath;
        }

        public void setClientKeyPath(String clientKeyPath) {
            this.clientKeyPath = clientKeyPath;
        }
    }

    /** 鉴权配置；token 不写进配置文件，优先使用环境变量或 secret 文件。 */
    public static class Auth {
        private String token;
        private String tokenFile;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getTokenFile() {
            return tokenFile;
        }

        public void setTokenFile(String tokenFile) {
            this.tokenFile = tokenFile;
        }
    }

    /** ID 生成配置。 */
    public static class Id {
        private String name = "application";
        private int batchSize = 1000;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    /** 编号配置。 */
    public static class Numbering {
        private String prefix = "numbering";

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }
    }

    /** 全局序列配置。 */
    public static class Clock {
        private String name = "global-sequencer";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** 分布式锁配置。 */
    public static class Lock {
        /** 持有者标识；空 = {@code host:pid:<随机后缀>}（每个 JVM 一个）。 */
        private String holderId;

        public String getHolderId() {
            return holderId;
        }

        public void setHolderId(String holderId) {
            this.holderId = holderId;
        }
    }

    /** EventBus 发布/消费配置。 */
    public static class EventBus {
        private int partitions = 4;
        private String producer;
        private int consumerMaxMessages;
        private int consumerScanLimit = 1000;

        public int getPartitions() {
            return partitions;
        }

        public void setPartitions(int partitions) {
            this.partitions = partitions;
        }

        public String getProducer() {
            return producer;
        }

        public void setProducer(String producer) {
            this.producer = producer;
        }

        /** 每次 poll 向 agent 要的最大条数；0 = agent 默认。 */
        public int getConsumerMaxMessages() {
            return consumerMaxMessages;
        }

        public void setConsumerMaxMessages(int consumerMaxMessages) {
            this.consumerMaxMessages = consumerMaxMessages;
        }

        /** 每次 poll 扫描投递账本的上界；账本大小 ≈ 在飞消息数。 */
        public int getConsumerScanLimit() {
            return consumerScanLimit;
        }

        public void setConsumerScanLimit(int consumerScanLimit) {
            this.consumerScanLimit = consumerScanLimit;
        }
    }
}
