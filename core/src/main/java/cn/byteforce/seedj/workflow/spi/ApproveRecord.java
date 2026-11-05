package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 审批记录。
 *
 * <p>{@code assignee} 是实际操作人；代理人操作时 {@code onBehalfOf} 是被代理人。
 * 委托关系不在 assignment 层重复存储。
 */
public record ApproveRecord(
        String assignee, String onBehalfOf, ActionDecision decision,
        String comment, Instant decidedAt, Map<String, String> extra
) {
    public ApproveRecord {
        Objects.requireNonNull(assignee, "assignee cannot be null");
        Objects.requireNonNull(decision, "decision cannot be null");
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
