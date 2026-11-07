package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.ScopePredicate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 命令类型到注册项的执行面注册表，并在这里统一执行命令级授权。
 *
 * <p>重复命令类型、缺少权限声明都在启动期失败：一条没有权限的命令不允许“默认放行”。 未知类型按无效载荷拒绝，不静默回退。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CommandCatalog {

    private final Map<String, CommandRegistration> byType;

    private final CommandPolicy policy;

    private final CommandRuntime runtime;

    private final WriteScopeProvider writeScopes;

    /**
     * 构造注册表。
     *
     * @param registrations 命令注册项
     * @param policy 命令级授权策略
     * @param runtime 命令运行时
     * @param writeScopes 写范围提供者
     */
    public CommandCatalog(
            List<CommandRegistration> registrations,
            CommandPolicy policy,
            CommandRuntime runtime,
            WriteScopeProvider writeScopes) {
        Objects.requireNonNull(registrations, "registrations");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.writeScopes = Objects.requireNonNull(writeScopes, "writeScopes");
        Map<String, CommandRegistration> copy = new LinkedHashMap<>();
        for (CommandRegistration registration : registrations) {
            Objects.requireNonNull(registration, "registration");
            CommandDescriptor descriptor = registration.descriptor();
            if (descriptor.permission() == null || descriptor.permission().isBlank()) {
                throw new IllegalArgumentException(
                        "command " + descriptor.type() + " must declare a permission");
            }
            CommandRegistration previous = copy.putIfAbsent(descriptor.type(), registration);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate command type: " + descriptor.type());
            }
        }
        this.byType = Map.copyOf(copy);
    }

    /**
     * 执行命令：先授权，再解析写范围并交给运行时。
     *
     * @param submission 命令提交
     * @param caller 调用方
     * @return 受理结果
     */
    public CommandReceipt execute(CommandSubmission submission, Caller caller) {
        Objects.requireNonNull(submission, "submission");
        CommandRegistration registration = byType.get(submission.type());
        if (registration == null) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD,
                    "unknown command type: " + submission.type());
        }
        policy.authorize(registration.descriptor(), caller, submission.payload());
        ScopePredicate scope = writeScopes.scope(registration.descriptor().objectType(), caller);
        if (scope == null) {
            throw new IllegalStateException(
                    "write scope provider returned null for "
                            + registration.descriptor().objectType());
        }
        return runtime.execute(submission, registration, caller, scope);
    }

    /**
     * 按类型查找注册项。
     *
     * @param type 命令类型
     * @return 注册项；未知为空
     */
    public Optional<CommandRegistration> find(String type) {
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
        for (CommandRegistration registration : byType.values()) {
            descriptors.add(registration.descriptor());
        }
        descriptors.sort(Comparator.comparing(CommandDescriptor::type));
        return List.copyOf(descriptors);
    }
}
