package cn.byteforce.seedj.starter.ai.approval;

import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 审批配置（{@code seedj.ai.approval.*}）：不可逆动作在决策面先走人工审批。
 *
 * <p>默认关闭：关闭时决策面对 {@code IRREVERSIBLE_*} 动作直接 deny，绝不签出无审批的 token。开启后 {@code approvers} 至少一个，且必须能找到
 * {@code WorkflowProvider}——审批引擎是端口，本地实现是 {@code seedj-workflow-local}，生产可换 coord 适配器。
 *
 * @param enabled 是否装配审批，默认 false
 * @param namespace 工作流定义的 namespace，默认 {@code seedj-ai}
 * @param name 工作流定义名，默认 {@code agent-approval}
 * @param version 定义版本（{@code major.minor}），默认 {@code 1.0}
 * @param approvers 审批人 ID（UUID 字符串）；{@code enabled=true} 时至少一个
 * @param deadline 审批时限，默认 4h
 * @param allowSelfApprove 是否允许发起人（{@code on-behalf-of}）自批，默认 false
 * @param notification 通知配置；未配置时默认开启
 * @param sweep 过期扫描间隔，默认 60s
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "seedj.ai.approval")
public record ApprovalProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("seedj-ai") String namespace,
        @DefaultValue("agent-approval") String name,
        @DefaultValue("1.0") String version,
        List<String> approvers,
        @DefaultValue("4h") Duration deadline,
        @DefaultValue("false") boolean allowSelfApprove,
        Notification notification,
        @DefaultValue("60s") Duration sweep) {

    /** {@code deadline} 默认值。 */
    public static final Duration DEFAULT_DEADLINE = Duration.ofHours(4);

    /** {@code sweep} 默认值。 */
    public static final Duration DEFAULT_SWEEP = Duration.ofSeconds(60);

    public ApprovalProperties {
        namespace = textOrDefault(namespace, "seedj-ai");
        name = textOrDefault(name, "agent-approval");
        version = textOrDefault(version, "1.0");
        approvers = approvers == null ? List.of() : List.copyOf(approvers);
        deadline = deadline == null ? DEFAULT_DEADLINE : deadline;
        sweep = sweep == null ? DEFAULT_SWEEP : sweep;
        notification = notification == null ? new Notification(true) : notification;
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("seedj.ai.approval.deadline 必须为正");
        }
        if (sweep.isZero() || sweep.isNegative()) {
            throw new IllegalArgumentException("seedj.ai.approval.sweep 必须为正");
        }
    }

    /**
     * 解析审批人 ID：逐项要求 UUID。
     *
     * @return 审批人 UUID 列表（不可变）
     * @throws IllegalStateException 任一项不是 UUID
     */
    public List<UUID> approverIds() {
        return approvers.stream().map(ApprovalProperties::approverId).toList();
    }

    /**
     * 审批工作流定义标识。
     *
     * @return {@code namespace + name}
     */
    public WorkflowDefinitionId definitionId() {
        return new WorkflowDefinitionId(namespace, name);
    }

    /**
     * 审批工作流定义版本。
     *
     * @return {@code major.minor}
     * @throws IllegalStateException 版本不是 {@code major.minor}
     */
    public WorkflowDefinitionVersion definitionVersion() {
        String[] parts = version.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalStateException(
                    "seedj.ai.approval.version 必须是 major.minor: " + version);
        }
        try {
            return new WorkflowDefinitionVersion(
                    Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException error) {
            throw new IllegalStateException(
                    "seedj.ai.approval.version 必须是 major.minor: " + version, error);
        }
    }

    private static UUID approverId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("seedj.ai.approval.approvers 含非 UUID: " + value, error);
        }
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * 通知配置。
     *
     * @param enabled 是否在审批开始/结束时通知审批人，默认 true；没有 IM 依赖时只 WARN
     */
    public record Notification(@DefaultValue("true") boolean enabled) {}
}
