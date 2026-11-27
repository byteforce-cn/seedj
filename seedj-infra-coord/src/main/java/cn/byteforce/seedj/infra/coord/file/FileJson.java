package cn.byteforce.seedj.infra.coord.file;

import tools.jackson.databind.json.JsonMapper;

/** 文件元数据记录的 JSON 编解码；与 {@code CoordJson} 分开，避免把模块级工具带进子包依赖。 */
final class FileJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private FileJson() {}

    static byte[] write(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    static <T> T read(byte[] value, Class<T> type) {
        return MAPPER.readValue(value, type);
    }
}
