package cn.byteforce.seedj.workflow.spi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 步骤定义。
 *
 * <p>构造期不变量：{@code HUMAN_TASK} 必须携带 {@link HumanTaskSpec}，其它类型不得携带； {@code WAIT_SIGNAL} 必须声明 {@code
 * waitSignalName}，其它类型不得声明； {@code HumanTaskSpec.dueIn} 必须 {@code <= timeout}。
 */
public record StepDefinition(
        String stepName,
        StepKind kind,
        Duration timeout,
        RetryPolicy retryPolicy,
        List<String> dependsOn,
        Map<String, String> metadata,
        HumanTaskSpec humanTask,
        String waitSignalName) {
    public StepDefinition {
        Objects.requireNonNull(stepName, "stepName cannot be null");
        Objects.requireNonNull(kind, "kind cannot be null");
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (kind == StepKind.HUMAN_TASK && humanTask == null)
            throw new IllegalArgumentException("HUMAN_TASK step requires humanTask spec");
        if (kind != StepKind.HUMAN_TASK && humanTask != null)
            throw new IllegalArgumentException("non-HUMAN_TASK step must not carry humanTask spec");
        if (kind == StepKind.WAIT_SIGNAL && (waitSignalName == null || waitSignalName.isBlank()))
            throw new IllegalArgumentException("WAIT_SIGNAL step requires waitSignalName");
        if (kind != StepKind.WAIT_SIGNAL && waitSignalName != null)
            throw new IllegalArgumentException(
                    "non-WAIT_SIGNAL step must not carry waitSignalName");
        if (humanTask != null
                && humanTask.dueIn() != null
                && timeout != null
                && humanTask.dueIn().compareTo(timeout) > 0) {
            throw new IllegalArgumentException(
                    "HumanTaskSpec.dueIn must be <= StepDefinition.timeout");
        }
    }

    public StepDefinition(
            String stepName,
            StepKind kind,
            Duration timeout,
            RetryPolicy retryPolicy,
            List<String> dependsOn,
            Map<String, String> metadata) {
        this(stepName, kind, timeout, retryPolicy, dependsOn, metadata, null, null);
    }

    public static StepDefinition waitSignal(
            String stepName, String waitSignalName, Duration timeout, List<String> dependsOn) {
        return new StepDefinition(
                stepName,
                StepKind.WAIT_SIGNAL,
                timeout,
                RetryPolicy.noRetry(),
                dependsOn,
                Map.of(),
                null,
                waitSignalName);
    }
}
