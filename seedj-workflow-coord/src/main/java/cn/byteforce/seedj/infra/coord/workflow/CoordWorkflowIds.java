package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.seedj.workflow.spi.HumanTaskNotFoundException;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * seedj 标识与 coord 标识的编解码。
 *
 * <p>coord 的实例 id 同时是 round/seat id 的前缀（{@code <instanceId>:round-NNNN}）， 所以实例 id 只允许
 * {@code [A-Za-z0-9._-]{1,128}}：含 {@code :} 会让 round 解码产生歧义。
 */
final class CoordWorkflowIds {

    static final String DEFINITION_NAMESPACE_SEGMENT = ":workflow:";

    private static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern ROUND_ID = Pattern.compile("round-\\d+");
    private static final Pattern DEFINITION_PART = Pattern.compile("[A-Za-z0-9._:-]+");

    private final String baseNamespace;

    CoordWorkflowIds(String baseNamespace) {
        this.baseNamespace = baseNamespace == null ? "" : baseNamespace;
    }

    /** seedj 定义命名空间 → coord 命名空间（{@code <base>:workflow:<seedjNs>}）。 */
    String coordNamespace(String seedjNamespace) {
        requireDefinitionPart(seedjNamespace, "seedj namespace");
        return baseNamespace + DEFINITION_NAMESPACE_SEGMENT + seedjNamespace;
    }

    /** coord 命名空间 → seedj 定义命名空间；不是本适配器的命名空间时返回 {@code null}。 */
    String seedjNamespace(String coordNamespace) {
        String prefix = baseNamespace + DEFINITION_NAMESPACE_SEGMENT;
        if (coordNamespace == null || !coordNamespace.startsWith(prefix)) {
            return null;
        }
        String seedjNamespace = coordNamespace.substring(prefix.length());
        return seedjNamespace.isBlank() ? null : seedjNamespace;
    }

    String coordName(WorkflowDefinitionId id) {
        requireDefinitionPart(id.name(), "seedj definition name");
        return id.name();
    }

    String coordVersion(WorkflowDefinitionVersion version) {
        return version.major() + "." + version.minor();
    }

    WorkflowDefinitionVersion seedjVersion(String coordVersion) {
        if (coordVersion != null) {
            String[] parts = coordVersion.split("\\.");
            if (parts.length >= 2) {
                try {
                    return new WorkflowDefinitionVersion(
                            Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                } catch (NumberFormatException ignored) {
                    // coord 接受任意 version 字符串；无法解析时按 0.0 暴露，不伪造语义。
                }
            }
        }
        return new WorkflowDefinitionVersion(0, 0);
    }

    void requireValidInstanceId(WorkflowInstanceId id) {
        if (!INSTANCE_ID.matcher(id.value()).matches()) {
            throw new WorkflowProviderException(
                    "workflow instance id must match " + INSTANCE_ID.pattern() + ": " + id.value());
        }
    }

    /** round id（seedj {@link TaskId}）→ 实例 id；后缀不是 {@code round-N} 时抛未找到。 */
    String instanceIdOf(TaskId taskId) {
        String value = taskId.value();
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new HumanTaskNotFoundException("not a coord round id: " + value);
        }
        String suffix = value.substring(separator + 1);
        if (!ROUND_ID.matcher(suffix).matches()) {
            throw new HumanTaskNotFoundException("not a coord round id: " + value);
        }
        return value.substring(0, separator);
    }

    /** 判断一个 coord task id 是否是 round id（seedj 语义），用于 history 的 seat→round 映射。 */
    boolean isRoundId(String coordTaskId) {
        if (coordTaskId == null) {
            return false;
        }
        Matcher matcher = ROUND_ID.matcher(coordTaskId);
        int separator = coordTaskId.lastIndexOf(':');
        return matcher.matches() && separator > 0;
    }

    private static void requireDefinitionPart(String value, String field) {
        if (value == null || !DEFINITION_PART.matcher(value).matches()) {
            throw new WorkflowProviderException(
                    field + " must match " + DEFINITION_PART.pattern() + ": " + value);
        }
    }
}
