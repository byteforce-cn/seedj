package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 命令类型到代理的注册表，并在这里统一执行命令级授权。
 *
 * <p>重复命令类型、缺少权限声明都在启动期失败：一条没有权限的命令不允许“默认放行”。
 * 未知类型按无效载荷拒绝，不静默回退。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CommandCatalog {

    private final Map<String, TemporalCommandAgent<?>> byType;

    private final CommandPolicy policy;

    /**
     * 构造注册表。
     *
     * @param agents 命令代理
     * @param policy 命令级授权策略
     */
    public CommandCatalog(List<TemporalCommandAgent<?>> agents, CommandPolicy policy) {
        Objects.requireNonNull(agents, "agents");
        this.policy = Objects.requireNonNull(policy, "policy");
        Map<String, TemporalCommandAgent<?>> copy = new LinkedHashMap<>();
        for (TemporalCommandAgent<?> agent : agents) {
            Objects.requireNonNull(agent, "agent");
            CommandDescriptor descriptor = agent.descriptor();
            if (descriptor.permission() == null || descriptor.permission().isBlank()) {
                throw new IllegalArgumentException(
                        "command " + descriptor.type() + " must declare a permission");
            }
            TemporalCommandAgent<?> previous = copy.putIfAbsent(descriptor.type(), agent);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate command type: " + descriptor.type());
            }
        }
        this.byType = Map.copyOf(copy);
    }

    /**
     * 执行命令：先授权，再交给代理。
     *
     * @param submission 命令提交
     * @param caller     调用方
     * @return 受理结果
     */
    public CommandReceipt execute(CommandSubmission submission, Caller caller) {
        Objects.requireNonNull(submission, "submission");
        TemporalCommandAgent<?> agent = byType.get(submission.type());
        if (agent == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "unknown command type: " + submission.type());
        }
        policy.authorize(agent.descriptor(), caller, submission.payload());
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

    /**
     * 已注册命令的描述，按类型排序。
     *
     * @return 不可变列表
     */
    public List<CommandDescriptor> descriptors() {
        List<CommandDescriptor> descriptors = new ArrayList<>();
        for (TemporalCommandAgent<?> agent : byType.values()) {
            descriptors.add(agent.descriptor());
        }
        descriptors.sort(Comparator.comparing(CommandDescriptor::type));
        return List.copyOf(descriptors);
    }
}
