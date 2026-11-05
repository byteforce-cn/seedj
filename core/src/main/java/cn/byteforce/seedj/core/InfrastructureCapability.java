package cn.byteforce.seedj.core;

import java.util.Objects;

/**
 * 基础设施能力初始化契约。
 *
 * <p>框架在启动期通过 {@code InfrastructureInitializer} 依次执行：
 * {@link #isReady()} 探针 → {@link #initialize()} 初始化 → {@link #validate()} 校验。
 * 需要保证存在的资源（数据库表、队列 topic 等）必须实现本接口，并且
 * {@link #isRequired()} 返回 {@code true}（默认值），初始化失败时阻断启动。
 *
 * <p>核心层只定义生命周期，不绑定任何数据库方言、迁移工具或消息中间件。
 */
public interface InfrastructureCapability {

    /**
     * 能力名称，用于日志与错误信息。
     */
    String getName();

    /**
     * 探针：资源是否已经就绪（表存在、迁移版本齐全、topic 存在等）。
     *
     * <p>实现必须可重复调用，且不得产生副作用。
     */
    boolean isReady();

    /**
     * 初始化：资源未就绪时执行创建/迁移。
     *
     * <p>实现必须幂等，允许并发启动的多个实例同时调用。
     */
    void initialize();

    /**
     * 初始化后的二次校验，防止“执行成功但实际未就绪”。
     *
     * <p>默认实现再次调用 {@link #isReady()}，未就绪时抛出
     * {@link InfrastructureException}（{@link InfrastructureErrorCode#NOT_READY}）。
     */
    default void validate() {
        if (!isReady()) {
            throw new InfrastructureException(InfrastructureErrorCode.NOT_READY,
                    "capability [" + getName() + "] is not ready after initialization");
        }
    }

    /**
     * 是否为核心必须能力。{@code false} 时初始化失败只记录 WARN，不阻断启动。
     */
    default boolean isRequired() {
        return true;
    }

    /**
     * 执行顺序，数字越小越先执行。
     */
    default int getOrder() {
        return 0;
    }
}
