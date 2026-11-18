package cn.byteforce.seedj.starter.ai.plane;

import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.starter.ai.AiProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 任务契约的能力集：一个任务能调哪些命令，只由 {@code seedj.ai.contracts[]} 声明。
 *
 * <p>它是决策面的授权锚点——账本记的是 {@code task_contract_id}，越权判定也只看这张表。<b>启动期校验</b>：契约标识非空白、
 * 不重复，每条契约至少有一个命令，且每个命令类型都已在 {@link ToolRegistry} 登记。最后一条让「允许但查不到动作分类」 成为不可能：动作分类只在工具注册表里，缺项的契约命令会让
 * deny 分支无法落账（见 {@link DecisionService}）。
 *
 * <p>不做「命令 → 契约」反查，也不缓存判定结果：调用方每步拿 {@code (task_contract_id, command.type)} 来问一次。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CapabilityRegistry {

    private final Map<String, AiProperties.Contract> byId;

    /**
     * 用配置的契约清单构造，并与工具注册表逐条交叉校验。
     *
     * @param contracts {@code seedj.ai.contracts[]} 绑定出的契约声明
     * @param tools 工具注册表：契约里的每个命令类型都必须已登记
     * @throws NullPointerException 任一参数或其中的条目为 {@code null}
     * @throws IllegalStateException 契约标识空白/重复、契约没有命令，或命令类型不在工具注册表中
     */
    public CapabilityRegistry(List<AiProperties.Contract> contracts, ToolRegistry tools) {
        Objects.requireNonNull(contracts, "contracts");
        Objects.requireNonNull(tools, "tools");
        Map<String, AiProperties.Contract> byId = new LinkedHashMap<>();
        for (AiProperties.Contract contract : contracts) {
            Objects.requireNonNull(contract, "contract");
            String id = contract.id();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("seedj.ai.contracts 存在缺少 id 的条目");
            }
            if (byId.containsKey(id)) {
                throw new IllegalStateException("seedj.ai.contracts 出现重复的契约 id: " + id);
            }
            if (contract.commands().isEmpty()) {
                throw new IllegalStateException("seedj.ai.contracts 的 " + id + " 没有任何命令");
            }
            for (String commandType : contract.commands()) {
                if (commandType == null || commandType.isBlank()) {
                    throw new IllegalStateException("seedj.ai.contracts 的 " + id + " 存在缺少命令类型的条目");
                }
                if (tools.find(commandType).isEmpty()) {
                    throw new IllegalStateException(
                            "seedj.ai.contracts 的 "
                                    + id
                                    + " 声明了未在 seedj.ai.tools 登记的命令类型: "
                                    + commandType);
                }
            }
            byId.put(id, contract);
        }
        this.byId = Map.copyOf(byId);
    }

    /**
     * 按契约标识查契约。
     *
     * @param taskContractId 契约标识（按 {@code toString()} 精确匹配）
     * @return 契约；未声明时为空
     */
    public Optional<AiProperties.Contract> find(String taskContractId) {
        return taskContractId == null
                ? Optional.empty()
                : Optional.ofNullable(byId.get(taskContractId));
    }

    /**
     * 判定契约是否允许某个命令类型。
     *
     * @param taskContractId 契约标识
     * @param commandType 命令类型
     * @return 契约存在且命令在它的 {@code commands} 内为 {@code true}；契约不存在恒为 {@code false}
     */
    public boolean allows(String taskContractId, String commandType) {
        return find(taskContractId)
                .map(contract -> commandType != null && contract.commands().contains(commandType))
                .orElse(false);
    }
}
