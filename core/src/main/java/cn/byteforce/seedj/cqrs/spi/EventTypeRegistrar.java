package cn.byteforce.seedj.cqrs.spi;

/**
 * 可选的事件类型注册钩子：持久化实现需要在读取历史前知道
 * “事件逻辑名 → 事件类”的映射。
 *
 * <p>内存实现直接保存对象，不需要注册；JDBC/对象存储必须把事件序列化成字节，
 * 读回时只能依赖逻辑名。{@link cn.byteforce.seedj.cqrs.EventSourcedRepository}
 * 在每次加载前调用本接口，把聚合实际处理的全部事件类型注册进去——这样应用重启后
 * 第一次读取也能解析，不需要业务方手工登记每个事件类。</p>
 *
 * <p>实现必须幂等：同一聚合类型重复注册不得报错。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EventTypeRegistrar {

    /**
     * 注册某个聚合类型会处理的事件类型。
     *
     * @param aggregateType 聚合类型（非空）
     */
    void register(Class<?> aggregateType);
}
