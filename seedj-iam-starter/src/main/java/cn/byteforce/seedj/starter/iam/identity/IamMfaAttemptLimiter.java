package cn.byteforce.seedj.starter.iam.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-process browser MFA throttle; native-auth has its own login limiter. */
public class IamMfaAttemptLimiter {

    private record Window(int failures, Instant resetAt) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    public IamMfaAttemptLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = Math.max(1, maxFailures);
        this.window = window == null ? Duration.ofMinutes(5) : window;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public boolean locked(String userId) {
        Window current = windows.get(userId);
        return current != null && current.failures() >= maxFailures
                && clock.instant().isBefore(current.resetAt());
    }

    public void failure(String userId) {
        Instant now = clock.instant();
        windows.compute(userId, (ignored, current) -> {
            if (current == null || now.isAfter(current.resetAt())) {
                return new Window(1, now.plus(window));
            }
            return new Window(current.failures() + 1, current.resetAt());
        });
    }

    public void success(String userId) {
        windows.remove(userId);
    }
}
