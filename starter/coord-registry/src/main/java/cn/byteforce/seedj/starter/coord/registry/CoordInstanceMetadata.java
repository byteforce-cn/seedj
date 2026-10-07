package cn.byteforce.seedj.starter.coord.registry;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实例 metadata 构建器：按约定 C1 生成注册用 JSON 字符串
 * （{@code address} 必填；{@code protocol} / {@code weight} 有默认值；
 * 用户附加键经 {@code byteforce.coord.registry.metadata.*} 合并，可覆盖内置键）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CoordInstanceMetadata {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private CoordInstanceMetadata() {
    }

    /**
     * 生成 metadata JSON。
     *
     * @param host       注册视角主机
     * @param port       注册端口
     * @param properties 注册属性（协议 / 权重 / 附加 metadata）
     * @return JSON 字符串（键序稳定：address、protocol、weight、附加键）
     * @throws CoordException 序列化异常（不应发生；发生即为内部错误）
     */
    public static String toJson(String host, int port, CoordRegistryProperties properties) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(CoordServiceInstance.KEY_ADDRESS, host + ":" + port);
        if (StringUtils.hasText(properties.getProtocol())) {
            fields.put(CoordServiceInstance.KEY_PROTOCOL, properties.getProtocol());
        }
        fields.put("weight", properties.getWeight());
        fields.putAll(properties.getMetadata());
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JacksonException e) {
            throw new CoordException(ErrorCode.INTERNAL, "实例 metadata 序列化失败", e);
        }
    }
}
