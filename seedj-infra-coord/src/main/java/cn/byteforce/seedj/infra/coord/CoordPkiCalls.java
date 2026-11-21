package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import java.util.function.Supplier;

/**
 * coord PKI 调用的统一重试与错误映射。
 *
 * <p>coord 的 PKI 存储建在 KV 上，刚写完就回读会撞上 {@code read_ts exceeds safe_ts}（agent
 * 在消息里写 {@code retry later}）。这里对<b>幂等</b>调用做有限重试；{@code rotateCert} 不是幂等的，
 * 重试会多转一次，所以调用方必须显式传 {@code idempotent=false}。
 */
final class CoordPkiCalls {

    private static final int RETRY_ATTEMPTS = 3;

    private final CoordEndpoint endpoint;

    CoordPkiCalls(CoordEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    <T> T call(String action, Supplier<T> body) {
        return call(action, true, body);
    }

    <T> T call(String action, boolean idempotent, Supplier<T> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return body.get();
            } catch (CoordException e) {
                if (idempotent && attempt < RETRY_ATTEMPTS && transientFailure(e)) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
                throw CoordErrors.pki(e, endpoint, action);
            }
        }
    }

    /** 可重试的传输/时序失败。{@code INTERNAL} 只在消息点名 safe_ts 回读滞后时重试。 */
    private static boolean transientFailure(CoordException e) {
        return switch (e.getErrorCode()) {
            case UNAVAILABLE,
                            DEADLINE_EXCEEDED,
                            NOT_LEADER,
                            SAFE_TS_LAG,
                            EPOCH_STALE,
                            TXN_CONFLICT,
                            COMMIT_REJECTED ->
                    true;
            case INTERNAL -> {
                String message = e.getMessage();
                yield message != null
                        && (message.contains("safe_ts") || message.contains("retry later"));
            }
            default -> false;
        };
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
