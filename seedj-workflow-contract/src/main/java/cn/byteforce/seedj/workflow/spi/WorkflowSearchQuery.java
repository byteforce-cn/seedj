package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;

/** 实例搜索；{@code cursor} 为 Provider 定义的不透明分页游标。 */
public record WorkflowSearchQuery(
        WorkflowDefinitionId definitionId,
        WorkflowStatus status,
        Instant startedAfter,
        Instant startedBefore,
        int limit,
        String cursor) {
    public WorkflowSearchQuery {
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
    }
}
