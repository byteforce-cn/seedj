package cn.byteforce.seedj.starter.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TraceContextFilter} 清洗规则单元测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class TraceContextFilterTest {

    @Test
    void sanitizeKeepsSafeCharacters() {
        assertThat(TraceContextFilter.sanitize("abc-123_X.Y")).isEqualTo("abc-123_X.Y");
    }

    @Test
    void sanitizeStripsUnsafeCharacters() {
        assertThat(TraceContextFilter.sanitize("abc 123;rm -rf!")).isEqualTo("abc123rm-rf");
    }

    @Test
    void sanitizeTrimsAndLimitsLength() {
        assertThat(TraceContextFilter.sanitize("  padded  ")).isEqualTo("padded");
        String longValue = "a".repeat(100);
        assertThat(TraceContextFilter.sanitize(longValue))
                .hasSize(TraceContextFilter.MAX_TRACE_ID_LENGTH);
    }

    @Test
    void sanitizeReturnsNullForEmptyOrInvalidInput() {
        assertThat(TraceContextFilter.sanitize(null)).isNull();
        assertThat(TraceContextFilter.sanitize("   ")).isNull();
        assertThat(TraceContextFilter.sanitize("！@#￥")).isNull();
    }
}
