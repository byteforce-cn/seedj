package cn.byteforce.seedj.cqrs.spi;

import cn.byteforce.seedj.cqrs.CommandRecord;

import java.util.Optional;
import java.util.UUID;

/**
 * 命令记录存储 SPI。
 *
 * <p>命令运行时按 {@code insert → complete} 两步写入：{@code insert} 在命令执行前
 * 落一条 {@code PENDING}，{@code complete} 在事务内（或拒绝路径的事务外）落终态。
 * 实现必须保证：</p>
 * <ul>
 *   <li>{@link #insert(CommandRecord)} 的 {@code id} 与 {@code idempotencyKey} 唯一；
 *       冲突返回 {@code false} 且不覆盖既有行；</li>
 *   <li>{@link #complete(CommandRecord)} 只允许 {@code PENDING → ACCEPTED/REJECTED}；
 *       同一终态重复调用幂等，其他改写抛 {@link IllegalStateException}；</li>
 *   <li>实现自身不开启事务：命令运行时保证对象行、命令记录、审计与事件同事务。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface CommandStore {

    /**
     * 按命令 ID 查询。
     *
     * @param id 命令 ID
     * @return 记录；不存在为空
     */
    Optional<CommandRecord> find(UUID id);

    /**
     * 按幂等键查询。
     *
     * @param idempotencyKey 幂等键
     * @return 记录；不存在为空
     */
    Optional<CommandRecord> findByIdempotencyKey(String idempotencyKey);

    /**
     * 插入一条待处理记录。
     *
     * @param record 待处理记录
     * @return {@code true} 表示插入成功；{@code false} 表示 ID 或幂等键已存在
     */
    boolean insert(CommandRecord record);

    /**
     * 将待处理记录推进到终态。
     *
     * @param record 终态记录
     * @throws IllegalStateException 记录不存在或已处于不同的终态
     */
    void complete(CommandRecord record);
}
