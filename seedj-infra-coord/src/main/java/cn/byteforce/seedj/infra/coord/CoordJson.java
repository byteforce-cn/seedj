package cn.byteforce.seedj.infra.coord;

import tools.jackson.databind.json.JsonMapper;

/**
 * coord 记录的统一 JSON 编解码。
 *
 * <p>只用于本模块自建的 KV 记录（锁、投递账本、密钥目录、策略索引）：这些记录不是领域事件， 不承诺跨版本兼容，字段变更随模块版本一起走。
 */
final class CoordJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CoordJson() {}

    static byte[] write(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    static <T> T read(byte[] value, Class<T> type) {
        return MAPPER.readValue(value, type);
    }
}
