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
 * 工单评论：把 {@code note} 写进 {@code last_comment} 并把 {@code comment_count} 加一。
 *
 * <p>走 {@code correct()}（更正追加），因此是「可逆」动作：新版本封口旧版本，历史仍可按时点读回。目标对象不存在时由
 * {@code requireCurrent} 直接 404，不猜。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CommentTicketHandler extends AbstractCommandHandler<CommentTicketCommand>
        implements CommandHandler<CommentTicketCommand> {

    private static final String COMMENT_COUNT = "comment_count";

    @Override
    public UUID targetId(CommentTicketCommand command) {
        return command.ticketId();
    }

    @Override
    public AppendRequest decide(CommentTicketCommand command, CommandContext ctx) {
        if (command.note() == null || command.note().isBlank()) {
            throw reject(ContractErrorCode.INVALID_PAYLOAD, "note must not be blank");
        }
        TemporalRow current = requireCurrent(ctx);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("last_comment", command.note());
        changes.put(COMMENT_COUNT, nextCount(current));
        return correct(ctx, merge(ctx, changes));
    }

    @Override
    public List<CommandEvent> events(
            CommentTicketCommand command, AppendResult result, CommandContext ctx) {
        return List.of(ctx.event("ticket.commented", result.appended().fields()));
    }

    private static int nextCount(TemporalRow current) {
        Object value = current.fields().get(COMMENT_COUNT);
        return value instanceof Number count ? count.intValue() + 1 : 1;
    }
}
