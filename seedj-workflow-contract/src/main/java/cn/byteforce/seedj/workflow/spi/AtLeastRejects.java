package cn.byteforce.seedj.workflow.spi;

/** 累计 {@code count} 个 REJECT 后终止任务并回退。 */
public record AtLeastRejects(int count) implements RejectCompletion {
    public AtLeastRejects {
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");
    }
}
