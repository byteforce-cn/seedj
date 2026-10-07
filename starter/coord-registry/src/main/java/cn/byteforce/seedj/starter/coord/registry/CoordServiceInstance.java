package cn.byteforce.seedj.starter.coord.registry;

import cn.byteforce.coord.sdk.registry.ServiceInstance;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.Optional;

/**
 * 消费侧的注册实例视图：包装 SDK {@link ServiceInstance}，并把约定 C1 的
 * metadata JSON（契约建议、服务端不解析）解析为可用字段。
 *
 * <p>metadata 解析是**尽力而为**的：缺失 / 非法 JSON / 非法字段一律降级为默认值
 * （既不抛错也不伪造数据），保证一个不规范实例不会拖垮发现链路。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CoordServiceInstance {

    /** metadata 约定键：{@code host:port}（C1）。 */
    public static final String KEY_ADDRESS = "address";

    /** metadata 约定键：协议标识（C1，默认 {@code http}）。 */
    public static final String KEY_PROTOCOL = "protocol";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 协议默认值（C1）。 */
    private static final String DEFAULT_PROTOCOL = "http";

    private final String serviceName;
    private final String instanceId;
    private final String metadata;

    /** 懒解析缓存；解析失败时为 {@link tools.jackson.databind.node.MissingNode}。 */
    private volatile JsonNode parsedMetadata;

    /**
     * @param serviceName 服务名
     * @param instanceId  实例 ID
     * @param metadata    原始 metadata JSON（可为空 / 非法，降级处理）
     */
    public CoordServiceInstance(String serviceName, String instanceId, String metadata) {
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.metadata = metadata;
    }

    /**
     * 从 SDK 实例构造。
     *
     * @param instance SDK 实例
     * @return 消费侧视图
     */
    public static CoordServiceInstance of(ServiceInstance instance) {
        return new CoordServiceInstance(instance.getServiceName(), instance.getInstanceId(), instance.getMetadata());
    }

    /**
     * 服务名。
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * 实例 ID。
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * 原始 metadata 字符串（消费端不做二次编码，保留原文便于诊断）。
     */
    public String getMetadata() {
        return metadata;
    }

    /**
     * 读取 metadata 字段（约定键或业务自定义键）。
     *
     * @param key 字段名
     * @return 字段值；缺失 / null 时为空
     */
    public Optional<String> getField(String key) {
        JsonNode node = parsed();
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        return Optional.of(value.asString());
    }

    /**
     * 实例地址（{@code host:port} 原文）。
     */
    public Optional<String> getAddress() {
        return getField(KEY_ADDRESS);
    }

    /**
     * 实例主机（从 {@code address} 解析，取最后一个 {@code ':'} 之前的部分）。
     */
    public Optional<String> getHost() {
        return getAddress().map(address -> {
            int idx = address.lastIndexOf(':');
            return idx > 0 ? address.substring(0, idx) : address;
        });
    }

    /**
     * 实例端口（从 {@code address} 解析）。
     *
     * @return 端口；缺失或非法时为 {@code -1}
     */
    public int getPort() {
        return getAddress().map(address -> {
            int idx = address.lastIndexOf(':');
            if (idx < 0 || idx == address.length() - 1) {
                return -1;
            }
            try {
                return Integer.parseInt(address.substring(idx + 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }).orElse(-1);
    }

    /**
     * 协议标识（C1，缺省 {@code http}）。
     */
    public String getProtocol() {
        return getField(KEY_PROTOCOL).filter(v -> !v.isBlank()).orElse(DEFAULT_PROTOCOL);
    }

    private JsonNode parsed() {
        JsonNode node = this.parsedMetadata;
        if (node == null) {
            node = parse();
            this.parsedMetadata = node;
        }
        return node;
    }

    private JsonNode parse() {
        if (metadata == null || metadata.isBlank()) {
            return emptyNode();
        }
        try {
            JsonNode node = MAPPER.readTree(metadata);
            return node == null ? emptyNode() : node;
        } catch (JacksonException e) {
            // 尽力而为：非法 JSON 视为无 metadata，不打断发现链路
            return emptyNode();
        }
    }

    private static JsonNode emptyNode() {
        return MAPPER.getNodeFactory().missingNode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CoordServiceInstance other)) {
            return false;
        }
        return Objects.equals(serviceName, other.serviceName)
                && Objects.equals(instanceId, other.instanceId)
                && Objects.equals(metadata, other.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceName, instanceId, metadata);
    }

    @Override
    public String toString() {
        return "CoordServiceInstance{" + serviceName + "/" + instanceId + ", metadata=" + metadata + '}';
    }
}
