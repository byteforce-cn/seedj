package cn.byteforce.seedj.starter.iam.nativeauth;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 登录节流窗口：阈值、窗口重置与成功清零。 */
class IamLoginRateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final IamLoginRateLimiter limiter = new IamLoginRateLimiter(3, Duration.ofMinutes(5),
            clock);

    @Test
    @DisplayName("未达阈值不锁定")
    void belowThresholdIsAllowed() {
        limiter.failure("default", "alice", "native-app");
        limiter.failure("default", "alice", "native-app");

        assertThatCode(() -> limiter.check("default", "alice", "native-app"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("达到阈值后锁定，窗口过后自动解锁")
    void thresholdLocksUntilWindowReset() {
        limiter.failure("default", "alice", "native-app");
        limiter.failure("default", "alice", "native-app");
        limiter.failure("default", "alice", "native-app");

        assertThatThrownBy(() -> limiter.check("default", "alice", "native-app"))
                .isInstanceOfSatisfying(IamException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(IamErrorCode.ACCOUNT_LOCKED));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThatCode(() -> limiter.check("default", "alice", "native-app"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("窗口过期后的失败重新计数")
    void failuresAfterWindowRestartCounter() {
        limiter.failure("default", "alice", "native-app");
        limiter.failure("default", "alice", "native-app");
        limiter.failure("default", "alice", "native-app");
        clock.advance(Duration.ofMinutes(6));

        limiter.failure("default", "alice", "native-app");

        assertThatCode(() -> limiter.check("default", "alice", "native-app"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("成功登录清零失败计数")
    void successClearsWindow() {
        limiter.failure("default", "alice", "native-app");
        limiter.failure("default", "alice", "native-app");

        limiter.success("default", "alice", "native-app");

        assertThatCode(() -> limiter.check("default", "alice", "native-app"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("用户名大小写归一化，空用户名与客户端可安全处理")
    void keyNormalizesCaseAndNulls() {
        limiter.failure("default", "Alice", null);
        limiter.failure("default", "ALICE", null);
        limiter.failure("default", "alice", null);

        assertThatThrownBy(() -> limiter.check("default", "aLiCe", null))
                .isInstanceOf(IamException.class);
    }

    @Test
    @DisplayName("阈值小于 1 时按 1 处理；window/clock 为 null 时取默认值")
    void constructorClampsDefaults() {
        IamLoginRateLimiter single = new IamLoginRateLimiter(0, null, null);

        single.failure("default", "bob", "native-app");

        assertThatThrownBy(() -> single.check("default", "bob", "native-app"))
                .isInstanceOf(IamException.class);
        assertThatCode(() -> new IamLoginRateLimiter(1, Duration.ofMinutes(1), clock)
                .check("default", "nobody", "native-app"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("窗口表超过 10000 条后整体清理")
    void clearsWindowsAboveCap() {
        limiter.failure("default", "alice", "native-app");
        limiter.failure("default", "alice", "native-app");
        limiter.failure("default", "alice", "native-app");

        for (int index = 0; index <= 10_000; index++) {
            limiter.failure("default", "user-" + index, "native-app");
        }

        assertThatCode(() -> limiter.check("default", "alice", "native-app"))
                .doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
