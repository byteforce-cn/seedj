package cn.byteforce.seedj.starter.iam.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code oauth2_authorization} 行的反序列化契约。
 *
 * <p>升级前 {@code IamTokenCustomizer} 把 {@code List.copyOf(...)} 写进 claims，落库的类型 id 是 {@code
 * java.util.ImmutableCollections$List12} / {@code $ListN}。这些行已经在生产库里，升级后第一次 refresh_token 交换也必须能读出。
 */
class IamAuthorizationJsonMapperTest {

    private final JsonMapper mapper = IamAuthorizationJsonMapper.create();

    @Test
    @DisplayName("旧行的 ImmutableCollections 类型 id 读成可再次持久化的 JDK 集合")
    void legacyImmutableCollectionTypeIdsAreReadable() {
        String legacy =
                """
                {"@class":"java.util.LinkedHashMap",
                 "sub":"u-alice",
                 "roles":["java.util.ImmutableCollections$List12",["reader","writer"]],
                 "groups":["java.util.ImmutableCollections$ListN",["sales","ops","hr"]],
                 "amr":["java.util.ImmutableCollections$Set12",["pwd"]],
                 "extra":["java.util.ImmutableCollections$Map1",{"k":"v"}]}
                """;

        Map<?, ?> claims = mapper.readValue(legacy, Map.class);

        assertThat(claims.get("roles")).isEqualTo(List.of("reader", "writer"));
        assertThat(claims.get("roles")).isExactlyInstanceOf(ArrayList.class);
        assertThat(claims.get("groups")).isExactlyInstanceOf(ArrayList.class);
        assertThat(claims.get("amr")).isExactlyInstanceOf(LinkedHashSet.class);
        assertThat(claims.get("extra")).isExactlyInstanceOf(LinkedHashMap.class);

        // 读过一次就会按新类型重写：写回的类型 id 必须仍可再读，刷新链才能连续成功
        String rewritten = mapper.writeValueAsString(claims);
        assertThat(rewritten).doesNotContain("ImmutableCollections");
        assertThat(mapper.readValue(rewritten, Map.class)).isEqualTo(claims);
    }

    @Test
    @DisplayName("SAS 自家类型仍可反序列化：放行面只增不减")
    void securityTypesKeepWorking() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("device_id", "device-1");
        attributes.put("count", 3);

        String json = mapper.writeValueAsString(attributes);

        assertThat(mapper.readValue(json, Map.class)).containsEntry("device_id", "device-1");
    }
}
