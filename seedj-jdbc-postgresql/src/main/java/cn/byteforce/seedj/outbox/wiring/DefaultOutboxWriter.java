package cn.byteforce.seedj.outbox.wiring;

import cn.byteforce.seedj.outbox.OutboxErrorCode;
import cn.byteforce.seedj.outbox.OutboxException;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 默认 outbox 写入实现：把记录交给当前可用的 {@link OutboxStore}。
 *
 * <p>store 用 {@link ObjectProvider} 延迟解析，避免 bean 注册顺序影响装配。
 * 没有 store 时在 append 阶段报 {@code STORE_UNAVAILABLE}，不静默丢弃。
 */
public class DefaultOutboxWriter implements OutboxWriter {

    private final ObjectProvider<OutboxStore> stores;

    public DefaultOutboxWriter(ObjectProvider<OutboxStore> stores) {
        this.stores = stores;
    }

    @Override
    public void append(OutboxRecord record) {
        OutboxStore store = stores.getIfAvailable();
        if (store == null) {
            throw new OutboxException(OutboxErrorCode.STORE_UNAVAILABLE,
                    "no OutboxStore bean; add an outbox implementation or disable seedj.outbox");
        }
        store.append(record);
    }
}
