package cn.byteforce.seedj.infra.jdbc.outbox.wiring;

import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.spi.ClaimRequest;
import cn.byteforce.seedj.outbox.spi.ClaimToken;
import cn.byteforce.seedj.outbox.spi.ClaimedRecord;
import cn.byteforce.seedj.outbox.spi.OutboxFailure;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/** relay 测试用的可控 store。 */
final class FakeOutboxStore implements OutboxStore {

    final List<ClaimedRecord> pending = new CopyOnWriteArrayList<>();
    final List<String> sent = new CopyOnWriteArrayList<>();
    final List<FailureCall> failed = new CopyOnWriteArrayList<>();
    final CountDownLatch sentLatch = new CountDownLatch(1);
    final CountDownLatch failedLatch = new CountDownLatch(1);

    volatile boolean renewalSupported;
    volatile boolean renewResult = true;
    volatile boolean closed;

    void enqueue(String recordId, OutboxRecord record, Duration lease) {
        pending.add(
                new ClaimedRecord(
                        recordId,
                        record,
                        1,
                        new ClaimToken(recordId, "lease-" + recordId, lease),
                        Instant.now().plus(lease)));
    }

    @Override
    public void append(OutboxRecord record) {
        throw new UnsupportedOperationException("append is not used by relay tests");
    }

    @Override
    public List<ClaimedRecord> claim(ClaimRequest request) {
        List<ClaimedRecord> claimed = new ArrayList<>();
        while (claimed.size() < request.limit() && !pending.isEmpty()) {
            claimed.add(pending.remove(0));
        }
        return claimed;
    }

    @Override
    public void markSent(ClaimToken token) {
        sent.add(token.recordId());
        sentLatch.countDown();
    }

    @Override
    public void markFailed(ClaimToken token, OutboxFailure failure, Duration retryDelay) {
        failed.add(new FailureCall(token, failure, retryDelay));
        failedLatch.countDown();
    }

    @Override
    public boolean supportsRenewal() {
        return renewalSupported;
    }

    @Override
    public boolean renewLease(ClaimToken token) {
        return renewResult;
    }

    @Override
    public void retry(String recordId, String operator) {}

    @Override
    public void discard(String recordId, String operator) {}

    @Override
    public void close() {
        closed = true;
    }

    record FailureCall(ClaimToken token, OutboxFailure failure, Duration delay) {}
}
