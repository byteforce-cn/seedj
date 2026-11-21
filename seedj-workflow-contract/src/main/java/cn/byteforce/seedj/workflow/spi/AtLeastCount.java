package cn.byteforce.seedj.workflow.spi;

/** 至少 {@code count} 个 APPROVE。 */
public record AtLeastCount(int count) implements SignCompletion {
    public AtLeastCount {
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");
    }
}
