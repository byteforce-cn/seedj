package cn.byteforce.seedj.outbox.spi;

import java.time.Duration;
import java.util.Objects;

/**
 * 领取凭证。写回状态（markSent / markFailed / renewLease）时必须原样带上。
 */
public record ClaimToken(String recordId, String leaseId, Duration leaseDuration) {

    public ClaimToken {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
    }
}
