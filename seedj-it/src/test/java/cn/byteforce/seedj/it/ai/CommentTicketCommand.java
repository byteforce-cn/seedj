package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.audit.Audited;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * pilot 命令载荷：给工单加一条评论（动作分类 {@code REVERSIBLE}）。
 *
 * <p>第二条命令类型的存在本身就是断言：T0 的写 pilot 必须同时覆盖 {@code reversible} 与 {@code compensable}
 * 两档动作分类。它带 {@code expected_version}，因此 token 与请求体里的版本必须一致（guard 复刻了
 * {@code If-Match} 的优先级）。
 *
 * @param ticketId 目标工单
 * @param note 评论内容（写入对象的 {@code last_comment}）
 * @param expectedVersion 期望对象版本
 * @author ByteForce
 * @since 1.0.0
 */
@Audited(action = "ticket.commented")
public record CommentTicketCommand(
        @JsonProperty("ticket_id") UUID ticketId,
        String note,
        @JsonProperty("expected_version") Long expectedVersion) {}
