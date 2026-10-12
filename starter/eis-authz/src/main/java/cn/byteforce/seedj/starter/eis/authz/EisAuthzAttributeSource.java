package cn.byteforce.seedj.starter.eis.authz;

import java.time.Instant;

/**
 * 属性源 SPI（ADR-0004 ATR-002「拉取为准」）：判定方按 {@code principal + as_of}
 * 拉取主体属性快照。典型实现调用 {@code 04-authz-contract/attributes.api.yaml}
 * 的拉取端点（{@code GET /principals/{id}/attributes?as_of=&recorded_as_of=}）。
 *
 * <p>失败口径（fail-closed，ATR-006）：</p>
 * <ul>
 *   <li>无快照（属主 409）→ 抛 {@link EisAuthzAttributeSourceException#missing(String)}；</li>
 *   <li>属主不可达/超时（503）→ 抛
 *       {@link EisAuthzAttributeSourceException#unavailable(String, Throwable)}；</li>
 *   <li>过期快照仍应返回（{@code attribute_state=stale}）供审计，由引擎拒绝依赖属性的条件。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface EisAuthzAttributeSource {

    /**
     * 拉取主体属性快照。
     *
     * @param principalRef 主体引用（ID 或业务键）
     * @param asOf         业务时点（与判定同一口径，半开区间）
     * @param recordedAsOf 系统时点（可空）
     * @return 属性快照（fresh / stale 均原样返回）
     * @throws EisAuthzAttributeSourceException 无快照（ATTRIBUTE_MISSING）或属主不可达（ATTRIBUTE_SOURCE_UNAVAILABLE）
     */
    EisAuthzAttributeSnapshot snapshot(String principalRef, Instant asOf, Instant recordedAsOf);
}
