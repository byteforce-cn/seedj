package cn.byteforce.seedj.outbox.spi;

import cn.byteforce.seedj.outbox.OutboxRecord;
import java.time.Instant;

/**
 * 已领取的一条 outbox 记录。
 *
 * @param id 存储行 id（运维 retry/discard 使用）
 * @param record 记录本身
 * @param attempts 已投递次数（含本次）
 * @param token 本次领取凭证
 * @param leaseUntil 租约到期时间
 */
public record ClaimedRecord(
        String id, OutboxRecord record, int attempts, ClaimToken token, Instant leaseUntil) {}
