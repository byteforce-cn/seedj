package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.infra.coord.fixture.FakeCoordBackend;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordKvStoreTest {

    private final FakeCoordBackend backend = new FakeCoordBackend();
    private final CoordKvStore store = new CoordKvStore(backend.kv(), backend.txn());

    @Test
    @DisplayName("putIfAbsent 只有第一次成功")
    void putIfAbsentIsExclusive() {
        assertThat(store.putIfAbsent("seedj:test:coord:kv:a", bytes("first"))).isTrue();
        assertThat(store.putIfAbsent("seedj:test:coord:kv:a", bytes("second"))).isFalse();

        assertThat(text(store.get("seedj:test:coord:kv:a").orElseThrow())).isEqualTo("first");
    }

    @Test
    @DisplayName("replace 只在期望值匹配时生效")
    void replaceRequiresExpectedValue() {
        store.put("seedj:test:coord:kv:a", bytes("v1"));

        assertThat(store.replace("seedj:test:coord:kv:a", bytes("stale"), bytes("v2"))).isFalse();
        assertThat(store.replace("seedj:test:coord:kv:a", bytes("v1"), bytes("v2"))).isTrue();
        assertThat(text(store.get("seedj:test:coord:kv:a").orElseThrow())).isEqualTo("v2");
    }

    @Test
    @DisplayName("removeIfMatch 不会删掉别人写入的新值")
    void removeOnlyDeletesMatchingValue() {
        store.put("seedj:test:coord:kv:a", bytes("v1"));

        assertThat(store.removeIfMatch("seedj:test:coord:kv:a", bytes("v2"))).isFalse();
        assertThat(store.get("seedj:test:coord:kv:a")).isPresent();

        assertThat(store.removeIfMatch("seedj:test:coord:kv:a", bytes("v1"))).isTrue();
        assertThat(store.get("seedj:test:coord:kv:a")).isEmpty();
    }

    @Test
    @DisplayName("scan 按前缀取记录，且不越界到相邻前缀")
    void scanStaysWithinPrefix() {
        store.put("seedj:test:coord:kv:a", bytes("1"));
        store.put("seedj:test:coord:kv:b", bytes("2"));
        store.put("seedj:test:coord:kvx:c", bytes("3"));
        store.put("seedj:test:coord:other:d", bytes("4"));

        List<CoordKvStore.Entry> entries = store.scan("seedj:test:coord:kv:", 10);

        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(CoordKvStoreTest::text)).containsExactly("1", "2");
    }

    @Test
    @DisplayName("scan 遵守 limit")
    void scanHonoursLimit() {
        store.put("seedj:test:coord:kv:a", bytes("1"));
        store.put("seedj:test:coord:kv:b", bytes("2"));

        assertThat(store.scan("seedj:test:coord:kv:", 1)).hasSize(1);
    }

    @Test
    @DisplayName("扫描上界是前缀末字节加一（按 UTF-8 字节）")
    void rangeEndIsPrefixPlusOne() {
        assertThat(CoordKeySpace.rangeEndOf("seedj:lock:"))
                .isEqualTo("seedj:lock;".getBytes(StandardCharsets.UTF_8));
        assertThat(CoordKeySpace.rangeEndOf("aÿ"))
                .isEqualTo(new byte[] {0x61, (byte) 0xC3, (byte) 0xC0});
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(CoordKvStore.Entry entry) {
        return new String(entry.value(), StandardCharsets.UTF_8);
    }
}
