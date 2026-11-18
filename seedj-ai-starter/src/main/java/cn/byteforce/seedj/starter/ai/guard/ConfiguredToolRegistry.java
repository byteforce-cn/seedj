package cn.byteforce.seedj.starter.ai.guard;

import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由配置（{@code seedj.ai.tools[]}）构造的工具注册表：启动期校验，运行期只查表。
 *
 * <p>命令类型与动作分类的唯一来源是命令注册方；本类只把配置里声明的那一部分挑出来变成白名单。因此「配了一条不存在的 命令」是装配错误而不是运行期拒绝：构造期抛 {@link
 * IllegalStateException} 并点名具体命令类型，应用启动失败。重复的 命令类型同样启动失败——同一命令两个动作分类会让审批强度取决于配置先后。
 *
 * <p>没有 {@link CommandCatalog} 的上下文（只开 guard、宿主没有命令）跳过交叉校验并打 {@code WARN}：这时只剩「非空 +
 * 不重复」两条本地校验，配置正确性由宿主的部署清单负责。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ConfiguredToolRegistry implements ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguredToolRegistry.class);

    private final Map<String, AgentToolSpec> byType;

    /**
     * 用配置的工具清单构造，并与命令目录逐条交叉校验。
     *
     * @param tools {@code seedj.ai.tools[]} 绑定出的工具声明
     * @param catalog 宿主的命令目录：配置的命令类型必须存在
     * @throws NullPointerException 任一参数或其中的条目为 {@code null}
     * @throws IllegalStateException 命令类型空白、重复，或不在命令目录中
     */
    public ConfiguredToolRegistry(List<AgentToolSpec> tools, CommandCatalog catalog) {
        this.byType = index(tools, Objects.requireNonNull(catalog, "catalog"));
    }

    /**
     * 没有命令目录时构造：跳过交叉校验，只在本地校验非空与不重复。
     *
     * @param tools {@code seedj.ai.tools[]} 绑定出的工具声明
     * @throws NullPointerException {@code tools} 或其中的条目为 {@code null}
     * @throws IllegalStateException 命令类型空白或重复
     */
    public ConfiguredToolRegistry(List<AgentToolSpec> tools) {
        this.byType = index(tools, null);
        if (!byType.isEmpty()) {
            LOG.warn("seedj: 未找到 CommandCatalog，seedj.ai.tools 的 {} 条命令未与命令目录交叉校验", byType.size());
        }
    }

    /**
     * 按命令类型查工具声明。
     *
     * @param commandType 命令类型
     * @return 工具声明；未在 {@code seedj.ai.tools[]} 登记时为空
     */
    @Override
    public Optional<AgentToolSpec> find(String commandType) {
        return commandType == null
                ? Optional.empty()
                : Optional.ofNullable(byType.get(commandType));
    }

    private static Map<String, AgentToolSpec> index(
            List<AgentToolSpec> tools, CommandCatalog catalog) {
        Objects.requireNonNull(tools, "tools");
        Map<String, AgentToolSpec> byType = new LinkedHashMap<>();
        for (AgentToolSpec tool : tools) {
            Objects.requireNonNull(tool, "tool");
            String commandType = tool.commandType();
            if (commandType == null || commandType.isBlank()) {
                throw new IllegalStateException("seedj.ai.tools 存在缺少 command-type 的条目");
            }
            if (byType.containsKey(commandType)) {
                throw new IllegalStateException("seedj.ai.tools 出现重复的命令类型: " + commandType);
            }
            if (catalog != null && catalog.find(commandType).isEmpty()) {
                throw new IllegalStateException("seedj.ai.tools 声明的命令类型不在命令目录中: " + commandType);
            }
            byType.put(commandType, tool);
        }
        return Map.copyOf(byType);
    }
}
