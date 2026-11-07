package cn.byteforce.seedj.id;

/**
 * 标识生成 SPI。
 *
 * <p>业务代码只依赖本接口，不关心 ID 是雪花、UUIDv7、数据库序列还是业务值对象：
 *
 * <pre>{@code
 * IdGenerator<Long> generator = ...;
 * long orderId = generator.id();
 *
 * IdGenerator<String> stringGenerator = ...;
 * String eventId = stringGenerator.id();
 * }</pre>
 *
 * <p><b>类型参数</b>：{@code ID} 由使用方决定。业务聚合的标识可以是 {@code Long}、{@code String}、{@code UUID}
 * 或自定义值对象；框架不对其编码方式 做假设，只要求生成器产出的类型与使用方的聚合标识类型一致。
 *
 * <h2>契约</h2>
 *
 * <ul>
 *   <li>实现必须线程安全，调用方不得加锁。
 *   <li>同一生成器实例返回的 ID 必须唯一；多服务部署时由实现方负责节点编号 或随机空间，调用方不需要知道。
 *   <li>实现可以额外保证单调/可排序，但唯一性是唯一强制项。
 *   <li>{@code close()} 幂等；关闭后再调用 {@link #id()} 抛 {@link
 *       cn.byteforce.seedj.core.InfrastructureException}。
 * </ul>
 *
 * <p>本接口 <b>不</b>提供"按 ID 反解时间"的能力：那是运维工具的事， 不应成为业务契约。
 *
 * @param <ID> 标识类型
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface IdGenerator<ID> extends AutoCloseable {

    /**
     * 生成下一个标识。
     *
     * @return 非空标识
     */
    ID id();

    @Override
    default void close() {}
}
