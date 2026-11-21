package cn.byteforce.seedj.read;

import cn.byteforce.seedj.domain.Page;
import java.util.Map;

/**
 * 自定义查询的执行出口：适配器负责 SQL 生成与执行。
 *
 * <p>{@link ReadGateway} 只做校验、对象权限、行范围解析、字段策略与审计，然后把 {@link QueryPlan} 交给实现。行范围谓词必须由实现编译成 SQL（列名限定为
 * {@code seedj_q}）， 不得忽略或放宽。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface QueryExecutor {

    /**
     * 执行一条已校验的查询计划。
     *
     * @param plan 查询计划
     * @return 一页行（列名 → 值）与总数；总数是行范围过滤后的条数
     */
    Page<Map<String, Object>> execute(QueryPlan plan);
}
