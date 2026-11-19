package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.audit.Audited;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * pilot 命令载荷：给工单记一笔退款（动作分类 {@code IRREVERSIBLE_FINANCIAL}）。
 *
 * <p>它不是真实资金接口：pilot 要验证的是「不可逆动作必须先审批」这条链，业务副作用用 {@code last_comment} 与 {@code comment_count}
 * 表达即可。载荷带 {@code expected_version}，因此 token 与请求体里的版本必须一致。
 *
 * @param ticketId 目标工单
 * @param amountCents 退款金额（分）
 * @param expectedVersion 期望对象版本
 * @author ByteForce
 * @since 1.0.0
 */
@Audited(action = "ticket.refunded")
public record RefundTicketCommand(
        @JsonProperty("ticket_id") UUID ticketId,
        @JsonProperty("amount_cents") Long amountCents,
        @JsonProperty("expected_version") Long expectedVersion) {}
