package cn.byteforce.seedj.workflow.spi;

/** 至少 {@code ceil(percentage * denominator)} 个 APPROVE；{@code percentage} 在 (0, 1]。 */
public record AtLeastPercentage(double percentage) implements SignCompletion {
    public AtLeastPercentage {
        if (percentage <= 0.0 || percentage > 1.0)
            throw new IllegalArgumentException("percentage must be in (0, 1]");
    }
}
