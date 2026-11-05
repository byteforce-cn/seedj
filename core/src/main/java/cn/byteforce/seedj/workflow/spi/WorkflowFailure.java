package cn.byteforce.seedj.workflow.spi;

import java.util.Map;
import java.util.Objects;

/** 结构化失败；不允许用裸异常字符串表达实例失败。 */
public record WorkflowFailure(String errorType, String errorMessage,
                              boolean retryable, String failedStep, Map<String, String> details) {
    public WorkflowFailure {
        Objects.requireNonNull(errorType, "errorType cannot be null");
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
