package cn.byteforce.seedj.test;

import cn.byteforce.seedj.it.extension.ClusterExtension;
import cn.byteforce.seedj.it.extension.ClusterService;
import cn.byteforce.seedj.it.extension.EnableClusterServices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transit 端到端：注入真实 bean（不是 {@code new BcTransitProvider()}），验证版本与错误码。
 */
@ExtendWith(ClusterExtension.class)
@EnableClusterServices({"im"})
class TransitIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("createKey → encrypt → decrypt → rotate，旧密文仍可解密")
    void roundtripAndRotation(@ClusterService("im") RestTestClient im) {
        Map<String, Object> body = get(im, "/api/public/test/transit/roundtrip");

        assertThat(body.get("decrypted")).isEqualTo("hello");
        assertThat(body.get("oldPlain")).isEqualTo("hello");
        assertThat(((Number) body.get("afterVersion")).intValue())
                .isGreaterThan(((Number) body.get("beforeVersion")).intValue());
        assertThat(((Number) body.get("latestVersion")).intValue())
                .isGreaterThanOrEqualTo(((Number) body.get("afterVersion")).intValue());
    }

    @Test
    @DisplayName("未知 key 返回稳定错误码 KEY_NOT_FOUND，且不可重试")
    void missingKeyHasErrorCode(@ClusterService("im") RestTestClient im) {
        Map<String, Object> body = get(im, "/api/public/test/transit/missing");

        assertThat(body.get("code")).isEqualTo("KEY_NOT_FOUND");
        assertThat(body.get("retryable")).isEqualTo(false);
    }

    private static Map<String, Object> get(RestTestClient client, String path) {
        var result = client.get().uri(path).exchange().returnResult(String.class);
        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        return json(result.getResponseBodyContent());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(byte[] body) {
        return JSON.readValue(new String(body, StandardCharsets.UTF_8), Map.class);
    }
}
