package cn.byteforce.seedj.core.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.spi.ClockConfig;
import cn.byteforce.seedj.core.clock.spi.ClockProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 时钟装配：按名称选择 provider、能力校验、失败降级与排序。 */
class ClockFactoryTest {

    private static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");

    @Test
    @DisplayName("按配置名选择 provider 并返回其时钟")
    void selectsProviderByName() {
        ClockProvider provider =
                provider(
                        "hlc",
                        ClockGuarantee.CROSS_NODE_CAUSAL,
                        5,
                        new TestClock(T0.plusSeconds(1)),
                        null);

        Clock clock = ClockFactory.resolve(config("hlc", null), List.of(provider));

        assertThat(clock.now()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    @DisplayName("provider 能力不足时装配期直接失败")
    void rejectsInsufficientGuarantee() {
        ClockProvider provider =
                provider("local", ClockGuarantee.LOCAL_MONOTONIC, 0, new TestClock(T0), null);

        assertThatThrownBy(
                        () ->
                                ClockFactory.resolve(
                                        config("local", ClockGuarantee.CROSS_NODE_TOTAL_ORDER),
                                        List.of(provider)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not satisfy required");
    }

    @Test
    @DisplayName("provider 名称不匹配时降级到 monotonic(system())")
    void fallsBackWhenNoProviderMatches() {
        ClockProvider provider =
                provider("hlc", ClockGuarantee.LOCAL_MONOTONIC, 0, new TestClock(T0), null);

        Clock clock = ClockFactory.resolve(config("missing", null), List.of(provider));

        Instant first = clock.now();
        Instant second = clock.now();
        assertThat(second).isAfter(first);
    }

    @Test
    @DisplayName("provider 构造失败时降级而不是中断启动")
    void fallsBackWhenProviderThrows() {
        ClockProvider provider =
                provider(
                        "broken",
                        ClockGuarantee.LOCAL_MONOTONIC,
                        0,
                        null,
                        new IllegalStateException("boom"));
        Instant before = Instant.now().minusSeconds(1);

        Clock clock = ClockFactory.resolve(config("broken", null), List.of(provider));

        assertThat(clock.now()).isStrictlyBetween(before, Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("配置与 provider 列表不可为空")
    void requiresArguments() {
        assertThatThrownBy(() -> ClockFactory.resolve(null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ClockFactory.resolve(config("x", null), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("降级链按 order 升序排列")
    void ordersProviders() {
        ClockProvider late =
                provider("late", ClockGuarantee.LOCAL_MONOTONIC, 9, new TestClock(T0), null);
        ClockProvider early =
                provider("early", ClockGuarantee.LOCAL_MONOTONIC, 1, new TestClock(T0), null);

        assertThat(ClockFactory.ordered(List.of(late, early)))
                .extracting(ClockProvider::name)
                .containsExactly("early", "late");
    }

    @Test
    @DisplayName("能力级别按 level 比较")
    void guaranteeSatisfaction() {
        assertThat(ClockGuarantee.CROSS_NODE_TOTAL_ORDER.satisfies(ClockGuarantee.LOCAL_MONOTONIC))
                .isTrue();
        assertThat(ClockGuarantee.LOCAL_MONOTONIC.satisfies(ClockGuarantee.CROSS_NODE_CAUSAL))
                .isFalse();
    }

    private static ClockConfig config(String provider, ClockGuarantee required) {
        return new ClockConfig(provider, required, "node-a", Map.of("zone", "utc"));
    }

    private static ClockProvider provider(
            String name,
            ClockGuarantee guarantee,
            int order,
            Clock clock,
            RuntimeException failure) {
        return new ClockProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ClockGuarantee guarantee() {
                return guarantee;
            }

            @Override
            public Clock create(ClockConfig config) {
                if (failure != null) {
                    throw failure;
                }
                return clock;
            }

            @Override
            public int order() {
                return order;
            }
        };
    }
}
