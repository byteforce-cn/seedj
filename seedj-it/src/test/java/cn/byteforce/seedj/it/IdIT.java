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
 * ID SPI 端到端：字符串 ID 由 IdGenerator 产生且单调递增。
 */
@ExtendWith(ClusterExtension.class)
@EnableClusterServices({"im"})
class IdIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("字符串 ID 非空且单调递增")
    void generatesSortableIds(@ClusterService("im") RestTestClient im) {
        var result = im.get().uri("/api/public/test/ids").exchange().returnResult(String.class);
        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(
                new String(result.getResponseBodyContent(), StandardCharsets.UTF_8), Map.class);

        assertThat(String.valueOf(body.get("id"))).isNotBlank();
        assertThat(body.get("monotonic")).isEqualTo(true);
    }
}
