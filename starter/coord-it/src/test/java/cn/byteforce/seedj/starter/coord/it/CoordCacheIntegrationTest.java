package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.cache.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式缓存（{@code CacheClient}）集成验证。
 *
 * <p>覆盖 String / Hash / List / Set 四类数据面操作经真实 Agent 的读写往返。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CoordCacheIntegrationTest extends AbstractCoordIntegrationTest {

    @Autowired
    private CacheClient cacheClient;

    @Test
    void stringSetGetDelete() {
        String key = uniqueName("it-cache/string");
        byte[] value = "你好, coord".getBytes(StandardCharsets.UTF_8);

        cacheClient.set(key, value, 0);
        assertThat(cacheClient.get(key)).isEqualTo(value);

        assertThat(cacheClient.delete(key)).isTrue();
        assertThat(cacheClient.get(key)).isNull();
        assertThat(cacheClient.delete(key)).isFalse();
    }

    @Test
    void hashOperations() {
        String key = uniqueName("it-cache/hash");
        cacheClient.hset(key, "f1", "v1".getBytes(StandardCharsets.UTF_8));
        cacheClient.hset(key, "f2", "v2".getBytes(StandardCharsets.UTF_8));

        assertThat(cacheClient.hget(key, "f1")).isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
        assertThat(cacheClient.hgetAll(key))
                .containsKeys("f1", "f2");
        assertThat(new String(cacheClient.hgetAll(key).get("f2"), StandardCharsets.UTF_8))
                .isEqualTo("v2");
    }

    @Test
    void listOperations() {
        String key = uniqueName("it-cache/list");
        assertThat(cacheClient.lpush(key, bytes("a"))).isEqualTo(1L);
        assertThat(cacheClient.lpush(key, bytes("b"))).isEqualTo(2L);

        // lpush 左进：最新元素在前
        assertThat(asStrings(cacheClient.lrange(key, 0, -1))).containsExactly("b", "a");
        // rpop 取尾部
        assertThat(cacheClient.rpop(key)).isEqualTo(bytes("a"));
        assertThat(cacheClient.llen(key)).isEqualTo(1L);
    }

    @Test
    void setOperations() {
        String key = uniqueName("it-cache/set");
        cacheClient.sadd(key, bytes("m1"));
        cacheClient.sadd(key, bytes("m2"));

        assertThat(asStrings(cacheClient.smembers(key))).containsExactlyInAnyOrder("m1", "m2");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> asStrings(List<byte[]> values) {
        return values.stream().map(value -> new String(value, StandardCharsets.UTF_8)).toList();
    }
}
