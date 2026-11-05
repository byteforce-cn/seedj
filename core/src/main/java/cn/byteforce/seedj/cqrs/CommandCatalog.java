package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 命令类型到代理的注册表。
 *
 * <p>重复命令类型在启动期直接失败；未知类型按无效载荷拒绝，不静默回退。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CommandCatalog {

    private final Map<String, TemporalCommandAgent<?>> byType;

    /**
     * 构造注册表。
     *
     * @param agents 命令代理
     */
    public CommandCatalog(List<TemporalCommandAgent<?>> agents) {
        Objects.requireNonNull(agents, "agents");
        Map<String, TemporalCommandAgent<?>> copy = new LinkedHashMap<>();
        for (TemporalCommandAgent<?> agent : agents) {
            TemporalCommandAgent<?> previous = copy.putIfAbsent(agent.commandType(), agent);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate command type: " + agent.commandType());
            }
        }
        this.byType = Map.copyOf(copy);
    }

    /**
     * 执行命令。
     *
     * @param submission 命令提交
     * @return 受理结果
     */
    public CommandReceipt execute(CommandSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        TemporalCommandAgent<?> agent = byType.get(submission.type());
        if (agent == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "unknown command type: " + submission.type());
        }
        return agent.execute(submission);
    }

    /**
     * 按类型查找代理。
     *
     * @param type 命令类型
     * @return 代理；未知为空
     */
    public Optional<TemporalCommandAgent<?>> find(String type) {
        return Optional.ofNullable(type == null ? null : byType.get(type));
    }

    /**
     * 已注册的命令类型。
     *
     * @return 不可变集合
     */
    public Set<String> types() {
        return byType.keySet();
    }
}
