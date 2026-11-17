package cn.byteforce.seedj.starter.im;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IM 长连接配置（{@code seedj.im.*}）。
 *
 * <p>所有默认值在 record 紧凑构造器里兜底，空绑定即可启动。布尔项用包装类型， 以便区分「未配置」与显式 {@code false}。
 *
 * @param enabled 总开关，默认 true
 * @param store 存储形态，默认 {@link Store#MEMORY}
 * @param push 推送配置
 * @param tcp TCP 传输配置
 * @param ws WebSocket 传输配置
 * @param heartbeatTimeout 读空闲超时，默认 90s
 * @param maxFrameLength 单帧最大字节数，默认 1MB
 * @param maxSessionsPerUser 单用户会话数上限，默认 1000
 * @param sessionOverflow 超出会话上限时的行为，默认拒绝新连接
 * @param message 消息配置
 * @param sync 同步配置
 * @param executor 命令执行器配置
 * @param http HTTP 管理端点配置
 */
@ConfigurationProperties(prefix = "seedj.im")
public record ImProperties(
        Boolean enabled,
        Store store,
        Push push,
        Tcp tcp,
        Ws ws,
        Duration heartbeatTimeout,
        int maxFrameLength,
        int maxSessionsPerUser,
        SessionOverflow sessionOverflow,
        Message message,
        Sync sync,
        Executor executor,
        Http http) {

    public static final String DEFAULT_PUSH_PROVIDER = "local";
    public static final int DEFAULT_TCP_PORT = 9090;
    public static final int DEFAULT_WS_PORT = 9091;
    public static final String DEFAULT_WS_PATH = "/ws";
    public static final Duration DEFAULT_HEARTBEAT_TIMEOUT = Duration.ofSeconds(90);
    public static final int DEFAULT_MAX_FRAME_LENGTH = 1_048_576;
    public static final int DEFAULT_MAX_SESSIONS_PER_USER = 1000;
    public static final int DEFAULT_MAX_BODY_BYTES = 65_536;
    public static final int DEFAULT_SYNC_PAGE_SIZE = 200;

    public ImProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        store = store == null ? Store.MEMORY : store;
        push = push == null ? new Push(null, null) : push;
        tcp = tcp == null ? new Tcp(null, DEFAULT_TCP_PORT) : tcp;
        ws = ws == null ? new Ws(null, DEFAULT_WS_PORT, null) : ws;
        if (heartbeatTimeout == null
                || heartbeatTimeout.isZero()
                || heartbeatTimeout.isNegative()) {
            heartbeatTimeout = DEFAULT_HEARTBEAT_TIMEOUT;
        }
        maxFrameLength = maxFrameLength <= 0 ? DEFAULT_MAX_FRAME_LENGTH : maxFrameLength;
        maxSessionsPerUser =
                maxSessionsPerUser <= 0 ? DEFAULT_MAX_SESSIONS_PER_USER : maxSessionsPerUser;
        sessionOverflow = sessionOverflow == null ? SessionOverflow.REJECT : sessionOverflow;
        message = message == null ? new Message(null, null, null, null, null) : message;
        sync = sync == null ? new Sync(null, null) : sync;
        executor = executor == null ? new Executor(null, null) : executor;
        http = http == null ? new Http(null) : http;
    }

    /**
     * 全默认值实例：供测试与编程式装配使用。
     *
     * @return 默认配置
     */
    public static ImProperties defaults() {
        return new ImProperties(
                null, null, null, null, null, null, 0, 0, null, null, null, null, null);
    }

    /** 总开关是否打开。 */
    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    /** 存储形态。 */
    public enum Store {

        /** 进程内存储：单实例开发与测试，重启即丢。 */
        MEMORY,

        /** PostgreSQL 存储：五张 {@code im_*} 表 + {@code im_seq}。 */
        JDBC
    }

    /** 单用户会话数超限时的行为。 */
    public enum SessionOverflow {

        /** 拒绝新连接（回 429 并关闭）。 */
        REJECT,

        /** 关掉最早建立的连接，再放行新连接。 */
        EVICT_OLDEST
    }

    /**
     * 推送配置。
     *
     * @param provider 推送提供方：{@code local}（默认）或 {@code coord}
     * @param nodeId 节点标识；coord 模式下用稳定值区分每个节点的消费组，空则进程启动时随机生成
     */
    public record Push(String provider, String nodeId) {

        public Push {
            provider =
                    provider == null || provider.isBlank()
                            ? DEFAULT_PUSH_PROVIDER
                            : provider.trim().toLowerCase(Locale.ROOT);
            nodeId = nodeId == null || nodeId.isBlank() ? null : nodeId.trim();
        }
    }

    /**
     * TCP 传输配置。
     *
     * @param enabled 是否启用，默认 true
     * @param port 监听端口；0 表示由系统分配
     */
    public record Tcp(Boolean enabled, int port) {

        public Tcp {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }

        /** 是否启用 TCP。 */
        public boolean isEnabled() {
            return !Boolean.FALSE.equals(enabled);
        }
    }

    /**
     * WebSocket 传输配置。
     *
     * @param enabled 是否启用，默认 true
     * @param port 监听端口；0 表示由系统分配
     * @param path 握手路径，默认 {@code /ws}
     */
    public record Ws(Boolean enabled, int port, String path) {

        public Ws {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            path = path == null || path.isBlank() ? DEFAULT_WS_PATH : path;
        }

        /** 是否启用 WebSocket。 */
        public boolean isEnabled() {
            return !Boolean.FALSE.equals(enabled);
        }
    }

    /**
     * 消息配置。
     *
     * @param maxBodyBytes 单条消息体上限，默认 64KB
     * @param retention 保留期；空表示永久保留
     * @param maxAttachmentBytes 单个附件引用的大小上限，默认 100MiB
     * @param retentionSweep 清理任务扫描间隔，默认 1 小时
     * @param retentionBatch 单批删除条数，默认 500
     */
    public record Message(
            Integer maxBodyBytes,
            Duration retention,
            Integer maxAttachmentBytes,
            Duration retentionSweep,
            Integer retentionBatch) {

        public static final int DEFAULT_MAX_ATTACHMENT_BYTES = 104_857_600;
        public static final Duration DEFAULT_RETENTION_SWEEP = Duration.ofHours(1);
        public static final int DEFAULT_RETENTION_BATCH = 500;

        public Message {
            int limit = maxBodyBytes == null ? DEFAULT_MAX_BODY_BYTES : maxBodyBytes;
            maxBodyBytes = limit <= 0 ? DEFAULT_MAX_BODY_BYTES : limit;
            if (retention != null && (retention.isZero() || retention.isNegative())) {
                retention = null;
            }
            int attachmentLimit =
                    maxAttachmentBytes == null ? DEFAULT_MAX_ATTACHMENT_BYTES : maxAttachmentBytes;
            maxAttachmentBytes =
                    attachmentLimit <= 0 ? DEFAULT_MAX_ATTACHMENT_BYTES : attachmentLimit;
            if (retentionSweep == null || retentionSweep.isZero() || retentionSweep.isNegative()) {
                retentionSweep = DEFAULT_RETENTION_SWEEP;
            }
            int batch = retentionBatch == null ? DEFAULT_RETENTION_BATCH : retentionBatch;
            retentionBatch = batch <= 0 ? DEFAULT_RETENTION_BATCH : batch;
        }
    }

    /**
     * HTTP 管理端点配置。
     *
     * @param enabled 是否注册 {@code /im/conversations} 端点，默认 true
     */
    public record Http(Boolean enabled) {

        public Http {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }

        /** 是否启用 HTTP 端点。 */
        public boolean isEnabled() {
            return !Boolean.FALSE.equals(enabled);
        }
    }

    /**
     * 命令执行器配置：命令处理在 Netty IO 线程之外执行，按调用方分流。
     *
     * @param threads 泳道数，默认 4（每条泳道单线程，跨泳道并行）
     * @param queueCapacity 每条泳道的队列容量，默认 4096；满时回 {@code 429}
     */
    public record Executor(Integer threads, Integer queueCapacity) {

        public static final int DEFAULT_THREADS = 4;

        public static final int DEFAULT_QUEUE_CAPACITY = 4096;

        public Executor {
            int lanes = threads == null ? DEFAULT_THREADS : threads;
            threads = lanes <= 0 ? DEFAULT_THREADS : lanes;
            int capacity = queueCapacity == null ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
            queueCapacity = capacity <= 0 ? DEFAULT_QUEUE_CAPACITY : capacity;
        }
    }

    /**
     * 同步配置。
     *
     * @param pageSize 单次增量拉取的最大条数，默认 200
     * @param replayHistory 入群前历史是否回放，默认 false
     */
    public record Sync(Integer pageSize, Boolean replayHistory) {

        public Sync {
            int size = pageSize == null ? DEFAULT_SYNC_PAGE_SIZE : pageSize;
            pageSize = size <= 0 ? DEFAULT_SYNC_PAGE_SIZE : size;
            replayHistory = replayHistory == null ? Boolean.FALSE : replayHistory;
        }

        /** 是否回放入群前历史。 */
        public boolean isReplayHistory() {
            return Boolean.TRUE.equals(replayHistory);
        }
    }
}
