package cn.byteforce.seedj.starter.iam.nativeauth;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-instance login throttle. Multi-instance deployments should move this to Redis/SQL. */
public class IamLoginRateLimiter {

    private record Window(int failures, Instant resetAt) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    public IamLoginRateLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = Math.max(1, maxFailures);
        this.window = window == null ? Duration.ofMinutes(5) : window;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void check(String tenantId, String username, String clientId) {
        Window current = windows.get(key(tenantId, username, clientId));
        if (current != null && current.failures() >= maxFailures
                && clock.instant().isBefore(current.resetAt())) {
            throw new IamException(IamErrorCode.ACCOUNT_LOCKED,
                    "too many failed login attempts; try again later");
        }
    }

    public void failure(String tenantId, String username, String clientId) {
        String key = key(tenantId, username, clientId);
        Instant now = clock.instant();
        if (windows.size() > 10_000) {
            windows.clear();
        }
        windows.compute(key, (ignored, current) -> {
            if (current == null || now.isAfter(current.resetAt())) {
                return new Window(1, now.plus(window));
            }
            return new Window(current.failures() + 1, current.resetAt());
        });
    }

    public void success(String tenantId, String username, String clientId) {
        windows.remove(key(tenantId, username, clientId));
    }

    private static String key(String tenantId, String username, String clientId) {
        return tenantId + "\u0000" + (username == null ? "" : username.toLowerCase())
                + "\u0000" + (clientId == null ? "" : clientId);
    }
}
