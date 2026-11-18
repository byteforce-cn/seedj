package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.audit.Audited;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * pilot 命令载荷：创建工单（动作分类 {@code COMPENSABLE}，见 {@code seedj.ai.tools[]} 与 {@link TicketModule}）。
 *
 * <p>载荷字段与 {@link Ticket} 同名的按 snake_case 映射进对象，{@code internal_note} 是 {@code writeOnly}——守卫仍按
 * <b>未脱敏原文</b>算 {@code args_hash}，所以它出现在载荷里不影响 202，只是读路径永远不返回它。
 *
 * @param title 标题
 * @param status 状态；缺省由 handler 补 {@code open}
 * @param assignee 受理人
 * @param internalNote 内部备注（写入专用）
 * @author ByteForce
 * @since 1.0.0
 */
@Audited(action = "ticket.created")
public record CreateTicketCommand(
        String title,
        String status,
        UUID assignee,
        @JsonProperty("internal_note") String internalNote) {}
