package cn.byteforce.seedj.cqrs;

/**
 * 一次读模型查询。
 *
 * <p>刻意是<b>可执行</b>的接口而不是空标记：查询模板不保证执行顺序（读侧没有顺序可言）， 它保证的是"拦截器能包住一次真实的读"，所以必须有东西可执行。空标记在这里会退化成 {@code
 * Object}——和命令侧把 {@code aggregateId} 拿掉是同一个错误。
 *
 * @param <R> 读取结果类型
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface Query<R> {

    /** 执行读取。只允许读投影/读模型，不得写任何东西。 */
    R execute();
}
