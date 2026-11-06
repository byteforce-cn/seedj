package cn.byteforce.seedj.read;

import java.util.List;
import java.util.Map;

/**
 * 已校验 SQL 的执行出口：适配器只负责执行，不再拼接或改写条件。
 *
 * <p>SQL 与参数由 {@link ReadGateway} 生成：行范围、分页都已绑定为位置参数；适配器不得
 * 自行解析、拼接或放宽条件。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface QueryExecutor {

    /**
     * 执行一页查询。
     *
     * @param sql        完整 SQL（含 LIMIT/OFFSET 占位符）
     * @param parameters 位置参数（与 SQL 顺序一致）
     * @return 行（列名 → 值）
     */
    List<Map<String, Object>> page(String sql, List<Object> parameters);

    /**
     * 执行计数查询。
     *
     * @param sql        计数 SQL
     * @param parameters 位置参数
     * @return 总条数
     */
    long count(String sql, List<Object> parameters);
}
