package cn.byteforce.seedj.cqrs;

/**
 * 一条要改变状态的指令。
 *
 * <p><b>必须携带目标聚合标识</b>，这不是可选字段：模板要在加载聚合之前就完成 "锁定哪个键、对哪条流做 CAS、这条命令是不是重复投递"三件事。标识由<b>边界</b> （应用服务 /
 * Controller）用 {@link cn.byteforce.seedj.id.IdGenerator} 分配， 命令对象一旦创建就是完整可执行的。
 *
 * <p>把标识放在命令上还顺带锁死了一条约束：<b>一条命令只作用一条聚合</b>。 多聚合命令在这里写不出来，只能拆成 saga——那才是它该有的位置。
 *
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface Command<ID> {

    /** 目标聚合标识，不允许为 {@code null}。 */
    ID aggregateId();
}
