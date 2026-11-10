package cn.byteforce.seedj.domain;

/**
 * 时间轴视图：当前、业务时点、完整历史。
 *
 * <p>当前视图 = 两条时间轴的右端都开放（{@code valid_to IS NULL AND superseded_at IS NULL}）， 必须命中 {@code
 * uq_*_current} 部分唯一索引，不得退化为全表 {@code DISTINCT ON}； 只判 {@code superseded_at IS NULL}
 * 会把业务上已关闭（软删/归档）的行误当成当前行。历史/时点视图才扫描版本。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum TemporalView {

    /** 当前版本（系统时间轴与业务时间轴都取当前）。 */
    CURRENT,

    /** 指定业务/系统时点。 */
    AS_OF,

    /** 某对象的全部版本。 */
    HISTORY;

    /**
     * 从查询参数推导视图。
     *
     * @param query 时点查询
     * @return 视图
     */
    public static TemporalView of(TemporalQuery query) {
        return query.asOf() == null && query.recordedAsOf() == null ? CURRENT : AS_OF;
    }
}
