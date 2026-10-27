package cn.byteforce.seedj.starter.eis.domain.spi;

import java.util.Optional;

/**
 * 当前租户解析：事件脊柱按租户隔离读写。
 *
 * <p>默认实现读 {@code RequestContext}。租户必填（{@code byteforce.eis.domain.tenant-required=true}，
 * 缺省值）时，解析不到租户的读写一律失败——跨租户泄漏比「查不到数据」严重得多。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface TenantResolver {

    /**
     * 返回当前租户。
     *
     * @return 当前租户；无租户上下文时为空
     */
    Optional<Long> currentTenantId();
}
