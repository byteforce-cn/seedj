package cn.byteforce.seedj.starter.im;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 命令字到处理器的注册表。
 *
 * <p>重复注册同一个命令字是启动失败，不是「后者覆盖前者」——覆盖会让宿主与 starter 的内置处理器 静默互相顶掉。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ImCommandRegistry {

    private final Map<Byte, ImCommandHandler<?>> handlers;

    /**
     * 由宿主 bean 集合构造注册表。
     *
     * @param handlers 处理器；{@code null} 视为空
     */
    public ImCommandRegistry(List<? extends ImCommandHandler<?>> handlers) {
        Map<Byte, ImCommandHandler<?>> map = new HashMap<>();
        if (handlers != null) {
            for (ImCommandHandler<?> handler : handlers) {
                Objects.requireNonNull(handler, "handler");
                ImCommandHandler<?> previous = map.putIfAbsent(handler.cmd(), handler);
                if (previous != null) {
                    throw new IllegalStateException(
                            "duplicate IM command handler for cmd 0x"
                                    + Integer.toHexString(handler.cmd())
                                    + ": "
                                    + previous.getClass().getName()
                                    + " and "
                                    + handler.getClass().getName());
                }
            }
        }
        this.handlers = Map.copyOf(map);
    }

    /**
     * 查找处理器。
     *
     * @param cmd 命令字
     * @return 处理器；未注册为空
     */
    public Optional<ImCommandHandler<?>> find(byte cmd) {
        return Optional.ofNullable(handlers.get(cmd));
    }

    /**
     * 已注册的命令字数量。
     *
     * @return 数量
     */
    public int size() {
        return handlers.size();
    }

    /**
     * 已注册的命令字。
     *
     * @return 命令字集合
     */
    public java.util.Set<Byte> commands() {
        return handlers.keySet();
    }
}
