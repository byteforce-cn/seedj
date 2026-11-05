package cn.byteforce.seedj.workflow.spi;

import java.util.Objects;

/** 人工任务标识。 */
public record TaskId(String value) {
    public TaskId {
        Objects.requireNonNull(value, "value cannot be null");
        if (value.isBlank()) throw new IllegalArgumentException("value cannot be blank");
    }
}
