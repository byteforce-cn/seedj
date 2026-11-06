package cn.byteforce.seedj.core.security;

/**
 * 请求事实写入扩展点：业务只实现它，不接触 SecurityContext。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ContextWriter {

    /**
     * 执行顺序，升序；默认 0。
     *
     * @return 顺序
     */
    default int order() {
        return 0;
    }

    /**
     * 写入事实。
     *
     * @param sink   写入端（拒绝重复 key）
     * @param caller 调用方
     */
    void write(ContextSink sink, Caller caller);
}
