package cn.byteforce.seedj.starter.im;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IM 长连接配置（{@code seedj.im.*}）。
 *
 * <p>所有默认值在 record 紧凑构造器里兜底，空绑定即可启动。布尔项用包装类型， 以便区分「未配置」与显式 {@code false}。
 *
 * @param enabled 总开关，默认 true
 * @param push 推送配置
 * @param tcp TCP 传输配置
 * @param ws WebSocket 传输配置
 * @param heartbeatTimeout 读空闲超时，默认 90s
 * @param maxFrameLength 单帧最大字节数，默认 1MB
 * @param maxSessionsPerUser 单用户会话数上限（阶段 2 入群校验）；默认 1000，本期不强制
 */
@ConfigurationProperties(prefix = "seedj.im")
public record ImProperties(
        Boolean enabled,
        Push push,
        Tcp tcp,
        Ws ws,
        Duration heartbeatTimeout,
        int maxFrameLength,
        int maxSessionsPerUser) {

    public static final String DEFAULT_PUSH_PROVIDER = "local";
    public static final int DEFAULT_TCP_PORT = 9090;
    public static final int DEFAULT_WS_PORT = 9091;
    public static final String DEFAULT_WS_PATH = "/ws";
    public static final Duration DEFAULT_HEARTBEAT_TIMEOUT = Duration.ofSeconds(90);
    public static final int DEFAULT_MAX_FRAME_LENGTH = 1_048_576;
    public static final int DEFAULT_MAX_SESSIONS_PER_USER = 1000;

    public ImProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        push = push == null ? new Push(null) : push;
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
    }

    /** 总开关是否打开。 */
    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    /**
     * 推送配置。
     *
     * @param provider 推送提供方，默认 {@code local}
     */
    public record Push(String provider) {

        public Push {
            provider = provider == null || provider.isBlank() ? DEFAULT_PUSH_PROVIDER : provider;
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
}
