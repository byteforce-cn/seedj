package cn.byteforce.seedj.server.hr.employee;

/**
 * 工号序列：本地/测试用内存实现，PostgreSQL 用数据库序列。
 *
 * <p>只负责分配数值，格式化（{@code EMP-%06d}）由 {@link HrEmployeeConfiguration} 的生成器统一完成。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EmployeeNoSequence {

    /**
     * 取下一个数值。
     *
     * @return 单调递增的数值
     */
    long next();
}
