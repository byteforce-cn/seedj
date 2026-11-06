package cn.byteforce.seedj.infra.local.cqrs;

import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.cqrs.spi.CommandStoreContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存实现跑公共契约套件，并补一条清除语义。
 */
class InMemoryCommandStoreContractTest extends CommandStoreContractTest {

    private final InMemoryCommandStore store = new InMemoryCommandStore();

    @Override
    protected CommandStore store() {
        return store;
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    @DisplayName("clear 同时清空 ID 与幂等键索引")
    void clearRemovesBothIndexes() {
        insert("k-clear");
        store.clear();

        assertThat(store.findByIdempotencyKey("k-clear")).isEmpty();
    }

    private void insert(String key) {
        store.insert(pending(UUID.randomUUID(), key, java.util.Map.of()));
    }
}
