package cn.byteforce.seedj.infra.local.transit;

import java.util.Map;
import java.util.function.Function;

/**
 * provider 的内部状态 seam：内存实现与磁盘实现共用同一套语义。
 *
 * <p>这是实现内部 seam（不是对外 SPI）：只有 {@link BcTransitProvider} 使用。
 */
interface TransitState {

    /** 当前状态快照。调用方不得修改返回值。 */
    Map<String, StoredKey> snapshot();

    /** 原子读-改-写：在锁内读取最新状态、应用变更并持久化。 */
    <T> T update(Function<Map<String, StoredKey>, T> mutation);
}
