package cn.byteforce.seedj.domain;

/**
 * 时间轴视图：当前、业务时点、完整历史。
 *
 * <p>当前视图必须命中 {@code superseded_at IS NULL} 的部分唯一索引，不得退化为
 * 全表 {@code DISTINCT ON}；历史/时点视图才扫描版本。</p>
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
