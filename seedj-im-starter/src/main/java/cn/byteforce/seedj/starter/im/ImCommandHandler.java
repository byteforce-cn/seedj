package cn.byteforce.seedj.starter.im;

/**
 * 一个 IM 命令字的处理器。
 *
 * <p>宿主注册一个 bean 就能新增命令字，不需要修改 {@link ImDispatchHandler}：分发按 {@link #cmd()} 查表，未注册的命令字仍回 {@code
 * 501}。
 *
 * <p>处理器运行在 Netty 的 IO 线程之外（由 starter 的会话执行器调度），因此可以直接访问数据库； 处理器内部抛出的 {@code ContractException}
 * 会被映射成协议错误码。
 *
 * @param <B> 请求体类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface ImCommandHandler<B> {

    /**
     * 该处理器负责的命令字。
     *
     * @return 命令字
     */
    byte cmd();

    /**
     * 请求体类型；body 为空时传 {@code null}。
     *
     * @return 请求体类型
     */
    Class<B> bodyType();

    /**
     * 处理一次请求。
     *
     * @param context 上下文
     * @param body 已解码的请求体；空 body 时为 {@code null}
     * @return 响应；{@code null} 表示不回包
     */
    ImReply handle(ImCallContext context, B body);
}
