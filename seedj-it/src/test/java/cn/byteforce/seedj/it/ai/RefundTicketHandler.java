package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.cqrs.AbstractCommandHandler;
import cn.byteforce.seedj.cqrs.CommandContext;
import cn.byteforce.seedj.cqrs.CommandHandler;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.event.CommandEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工单退款：把金额写进 {@code last_comment} 并把 {@code comment_count} 加一。
 *
 * <p>与评论同一条更正追加路径，区别只在动作分类（配置里是 {@code IRREVERSIBLE_FINANCIAL}）与事件类型。目标对象不存在时 {@code
 * requireCurrent} 直接 404。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class RefundTicketHandler extends AbstractCommandHandler<RefundTicketCommand>
        implements CommandHandler<RefundTicketCommand> {

    private static final String COMMENT_COUNT = "comment_count";

    @Override
    public UUID targetId(RefundTicketCommand command) {
        return command.ticketId();
    }

    @Override
    public AppendRequest decide(RefundTicketCommand command, CommandContext ctx) {
        if (command.amountCents() == null || command.amountCents() <= 0) {
            throw reject(ContractErrorCode.INVALID_PAYLOAD, "amount_cents must be positive");
        }
        TemporalRow current = requireCurrent(ctx);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("last_comment", "refund " + command.amountCents());
        changes.put(COMMENT_COUNT, nextCount(current));
        return correct(ctx, merge(ctx, changes));
    }

    @Override
    public List<CommandEvent> events(
            RefundTicketCommand command, AppendResult result, CommandContext ctx) {
        return List.of(ctx.event("ticket.refunded", result.appended().fields()));
    }

    private static int nextCount(TemporalRow current) {
        Object value = current.fields().get(COMMENT_COUNT);
        return value instanceof Number count ? count.intValue() + 1 : 1;
    }
}
