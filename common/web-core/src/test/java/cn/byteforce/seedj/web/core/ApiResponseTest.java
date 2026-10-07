package cn.byteforce.seedj.web.core;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiResponse} 序列化契约测试（护栏：P0 缺陷「序列化为 {}」不得回归）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class ApiResponseTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void successCarriesCodeMessageAndData() {
        JsonNode node = serialize(ApiResponse.success(42));

        assertThat(node.get("code").asInt()).isEqualTo(0);
        assertThat(node.get("message").asString()).isEqualTo("成功");
        assertThat(node.get("data").asInt()).isEqualTo(42);
        assertThat(node.get("traceId").isNull()).isTrue();
    }

    @Test
    void successWithoutDataUsesNullData() {
        JsonNode node = serialize(ApiResponse.success());

        assertThat(node.get("code").asInt()).isEqualTo(0);
        assertThat(node.get("data").isNull()).isTrue();
    }

    @Test
    void errorWithResponseCodeCarriesCodeAndMessage() {
        JsonNode node = serialize(ApiResponse.error(ResponseCode.BAD_REQUEST));

        assertThat(node.get("code").asInt()).isEqualTo(10001);
        assertThat(node.get("message").asString()).isEqualTo("请求参数错误");
        assertThat(node.get("data").isNull()).isTrue();
    }

    @Test
    void errorWithCustomMessageOverridesDefault() {
        JsonNode node = serialize(ApiResponse.error(ResponseCode.FAILED, "库存不足"));

        assertThat(node.get("code").asInt()).isEqualTo(10000);
        assertThat(node.get("message").asString()).isEqualTo("库存不足");
    }

    @Test
    void errorWithTraceIdPropagatesTraceId() {
        JsonNode node = serialize(ApiResponse.error(10001, "参数错误", "trace-1"));

        assertThat(node.get("code").asInt()).isEqualTo(10001);
        assertThat(node.get("traceId").asString()).isEqualTo("trace-1");
    }

    @Test
    void constructorExposesAllFields() {
        ApiResponse<String> response = new ApiResponse<>(7, "消息", "数据", "trace-2");

        assertThat(response.getCode()).isEqualTo(7);
        assertThat(response.getMessage()).isEqualTo("消息");
        assertThat(response.getData()).isEqualTo("数据");
        assertThat(response.getTraceId()).isEqualTo("trace-2");
    }

    private JsonNode serialize(ApiResponse<?> response) {
        return mapper.readTree(mapper.writeValueAsString(response));
    }
}
