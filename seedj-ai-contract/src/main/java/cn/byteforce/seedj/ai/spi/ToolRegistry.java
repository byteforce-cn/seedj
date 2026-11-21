package cn.byteforce.seedj.ai.spi;

import cn.byteforce.seedj.ai.AgentToolSpec;
import java.util.Optional;

/**
 * 工具注册表端口：命令类型 → 动作分类的唯一权威映射。
 *
 * <p>守卫不接受调用方自报的动作分类，只按本端口反查：注册方声明命令属于哪一档，审批要求就按哪一档执行。 未登记的命令类型视为不可调用（返回空），而不是当作最宽松的一档。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ToolRegistry {

    /**
     * 按命令类型查工具声明。
     *
     * @param commandType 命令类型
     * @return 工具声明；未登记时为空
     */
    Optional<AgentToolSpec> find(String commandType);
}
