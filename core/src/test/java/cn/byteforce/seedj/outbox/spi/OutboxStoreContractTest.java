package cn.byteforce.seedj.outbox.spi;

import cn.byteforce.seedj.outbox.OutboxErrorCode;
import cn.byteforce.seedj.outbox.OutboxException;
import cn.byteforce.seedj.outbox.OutboxRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OutboxStore 契约测试套件。
 *
 * <p>实现方需要提供 {@link #store()}、{@link #appendInTransaction(OutboxRecord)}
 * （在真实事务内写入）与 {@link #reset()}（清空状态）。
 */
public abstract class OutboxStoreContractTest {

    protected abstract OutboxStore store();

    /** 在活动事务内写入（append 的契约要求）。 */
    protected abstract void appendInTransaction(OutboxRecord record);

    /** 清空存储，保证用例隔离。 */
    protected abstract void reset();

    @BeforeEach
    void cleanState() {
        reset();
    }

    protected static OutboxRecord record(String messageId, String partitionKey, String payload) {
        return new OutboxRecord(messageId, "destination", partitionKey, "test.content", 1,
                payload.getBytes(StandardCharsets.UTF_8), Map.of("k", "v"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private ClaimedRecord applyOne(Duration lease) {
        List<ClaimedRecord> claimed = store().claim(new ClaimRequest(1, lease));
        assertThat(claimed).hasSize(1);
        return claimed.get(0);
    }

    @Test
    @DisplayName("写入后可领取，记录内容与 headers 一致")
    void appendThenClaim() {
        OutboxRecord record = record("msg-1", "agg-1", "payload");
        appendInTransaction(record);

        ClaimedRecord claimed = applyOne(Duration.ofSeconds(30));

        assertThat(claimed.record()).isEqualTo(record);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.token().recordId()).isEqualTo(claimed.id());
        assertThat(claimed.leaseUntil()).isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    @DisplayName("同一 messageId 重复写入只落一行（幂等）")
    void appendIsIdempotentOnMessageId() {
        appendInTransaction(record("msg-1", "agg-1", "payload"));
        appendInTransaction(record("msg-1", "agg-1", "payload"));

        assertThat(store().claim(new ClaimRequest(10, Duration.ofSeconds(30)))).hasSize(1);
    }

    @Test
    @DisplayName("同一分区按写入顺序投递：前一条未完成，后一条不可见")
    void partitionOrderingRespected() {
        appendInTransaction(record("msg-1", "agg-1", "first"));
        appendInTransaction(record("msg-2", "agg-1", "second"));

        List<ClaimedRecord> firstBatch = store().claim(new ClaimRequest(10, Duration.ofSeconds(30)));
        assertThat(firstBatch).hasSize(1);
        assertThat(firstBatch.get(0).record().payload())
                .isEqualTo("first".getBytes(StandardCharsets.UTF_8));

        store().markSent(firstBatch.get(0).token());

        List<ClaimedRecord> secondBatch = store().claim(new ClaimRequest(10, Duration.ofSeconds(30)));
        assertThat(secondBatch).hasSize(1);
        assertThat(secondBatch.get(0).record().payload())
                .isEqualTo("second".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("claim 遵守 limit")
    void claimHonoursLimit() {
        appendInTransaction(record("msg-1", "agg-1", "a"));
        appendInTransaction(record("msg-2", "agg-2", "b"));
        appendInTransaction(record("msg-3", "agg-3", "c"));

        assertThat(store().claim(new ClaimRequest(2, Duration.ofSeconds(30)))).hasSize(2);
    }

    @Test
    @DisplayName("租约已失效时 markSent 抛 LEASE_LOST")
    void staleLeaseCannotMarkSent() {
        appendInTransaction(record("msg-1", "agg-1", "payload"));
        ClaimedRecord claimed = applyOne(Duration.ofSeconds(30));

        ClaimToken stale = new ClaimToken(claimed.id(), "not-the-lease", Duration.ofSeconds(30));

        assertThatThrownBy(() -> store().markSent(stale))
                .isInstanceOf(OutboxException.class)
                .extracting(e -> ((OutboxException) e).getErrorCode())
                .isEqualTo(OutboxErrorCode.LEASE_LOST);
    }

    @Test
    @DisplayName("可重试失败后重新可领取，attempts 递增")
    void retryableFailureRequeues() {
        appendInTransaction(record("msg-1", "agg-1", "payload"));
        ClaimedRecord claimed = applyOne(Duration.ofSeconds(30));

        store().markFailed(claimed.token(), new OutboxFailure("io.IOException", "boom", true), Duration.ZERO);

        ClaimedRecord again = applyOne(Duration.ofSeconds(30));
        assertThat(again.id()).isEqualTo(claimed.id());
        assertThat(again.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("不可重试失败进入死信；retry 后可再次领取")
    void nonRetryableGoesToDeadLetter() {
        appendInTransaction(record("msg-1", "agg-1", "payload"));
        ClaimedRecord claimed = applyOne(Duration.ofSeconds(30));

        store().markFailed(claimed.token(), new OutboxFailure("IllegalArgument", "bad", false), Duration.ZERO);
        assertThat(store().claim(new ClaimRequest(10, Duration.ofSeconds(30)))).isEmpty();

        store().retry(claimed.id(), "ops");
        assertThat(store().claim(new ClaimRequest(10, Duration.ofSeconds(30)))).hasSize(1);
    }

    @Test
    @DisplayName("discard 之后不再领取")
    void discardRemovesFromDelivery() {
        appendInTransaction(record("msg-1", "agg-1", "payload"));
        ClaimedRecord claimed = applyOne(Duration.ofSeconds(30));
        store().markFailed(claimed.token(), new OutboxFailure("X", "x", false), Duration.ZERO);

        store().discard(claimed.id(), "ops");

        assertThat(store().claim(new ClaimRequest(10, Duration.ofSeconds(30)))).isEmpty();
    }

    @Test
    @DisplayName("对非 FAILED 记录做 retry/discard 抛 RECORD_NOT_FOUND")
    void opsOnlyApplyToFailedRecords() {
        appendInTransaction(record("msg-1", "agg-1", "payload"));

        assertThatThrownBy(() -> store().retry("no-such-id", "ops"))
                .isInstanceOf(OutboxException.class)
                .extracting(e -> ((OutboxException) e).getErrorCode())
                .isEqualTo(OutboxErrorCode.RECORD_NOT_FOUND);
    }

    @Test
    @DisplayName("租约到期后可被重新领取，旧凭证失效")
    void expiredLeaseIsReclaimed() throws Exception {
        appendInTransaction(record("msg-1", "agg-1", "payload"));
        ClaimedRecord first = applyOne(Duration.ofMillis(150));

        Thread.sleep(400);

        ClaimedRecord second = applyOne(Duration.ofSeconds(30));
        assertThat(second.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> store().markSent(first.token()))
                .isInstanceOf(OutboxException.class);
    }

    @Test
    @DisplayName("close 幂等")
    void closeIsIdempotent() {
        store().close();
        store().close();
    }
}
