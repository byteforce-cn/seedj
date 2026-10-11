package cn.byteforce.seedj.starter.eis.core;

import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 命令处理器 SPI：领域命令的核心实现（校验、状态变更、事件产出）。
 *
 * <p>一个处理器可声明多个命令类型；运行时按 {@code commandTypes()} 路由。
 * 处理器内通过 {@link EisCommandContext} 读写对象存储；拒绝时抛
 * {@link EisCommandRejectedException}（携带领域错误码）。</p>
 *
 * <p><b>敏感载荷</b>：{@link #storedPayload(JsonNode)} 返回落库的载荷视图
 * （默认原样）；凭证类命令在此脱敏（如剔除 {@code secret_hash} 字段），
 * 保证命令表、日志与事件不含敏感字段。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EisCommandHandler {

    /**
     * 支持的命令类型集合。
     *
     * @return 命令类型集合（不可空）
     */
    Set<String> commandTypes();

    /**
     * 执行命令。
     *
     * @param context 受理上下文
     * @return 事件草稿与主对象指针
     */
    EisCommandHandlerResult handle(EisCommandContext context);

    /**
     * 落库载荷视图（默认原样）。
     *
     * @param payload 原始载荷
     * @return 落库载荷
     */
    default JsonNode storedPayload(JsonNode payload) {
        return payload;
    }
}
