package cn.byteforce.seedj.numbering;

/**
 * 编号服务 SPI：按 scope 分配严格递增的数值，由调用方负责格式化。
 *
 * <p>scope 是稳定标识（如 {@code hr_employee}、{@code voucher}）；按年度重置使用
 * {@link #next(String, int)}，实现将其映射到 {@code scope_<year>} 的独立序列。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface Numbering {

    /**
     * 取下一个编号。
     *
     * @param scope 编号作用域（小写标识符）
     * @return 严格递增的数值
     */
    long next(String scope);

    /**
     * 取指定年度的下一个编号；不同年度互不影响。
     *
     * @param scope 编号作用域
     * @param year  年度
     * @return 严格递增的数值
     */
    default long next(String scope, int year) {
        if (year < 1970 || year > 9999) {
            throw new IllegalArgumentException("year must be between 1970 and 9999");
        }
        return next(scope + "_" + year);
    }
}
