package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.cqrs.AbstractCommandHandler;
import cn.byteforce.seedj.cqrs.CommandContext;
import cn.byteforce.seedj.cqrs.CommandHandler;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.event.CommandEvent;
import cn.byteforce.seedj.id.IdGenerator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工单创建：目标 ID 由 UUIDv7 生成，状态缺省 {@code open}。
 *
 * <p>与 HR 样板同一套写法：字段从命令 record 按 schema 映射（{@code schemaFields}），handler 只补计算字段。这样 「加字段只改 {@link
 * Ticket} 与命令 record」，不会出现第二份字段清单。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CreateTicketHandler extends AbstractCommandHandler<CreateTicketCommand>
        implements CommandHandler<CreateTicketCommand> {

    /** 创建时缺省的状态；显式给了就照给的走。 */
    static final String DEFAULT_STATUS = "open";

    private final IdGenerator<UUID> commandIds;

    /**
     * 构造处理器。
     *
     * @param commandIds 目标对象 ID 生成器
     */
    public CreateTicketHandler(IdGenerator<UUID> commandIds) {
        this.commandIds = commandIds;
    }

    @Override
    public UUID targetId(CreateTicketCommand command) {
        return commandIds.id();
    }

    @Override
    public AppendRequest decide(CreateTicketCommand command, CommandContext ctx) {
        if (command.title() == null || command.title().isBlank()) {
            throw reject(ContractErrorCode.INVALID_PAYLOAD, "title must not be blank");
        }
        Map<String, Object> fields = schemaFields(ctx, command);
        fields.putIfAbsent("status", DEFAULT_STATUS);
        return create(ctx, ctx.now(), fields);
    }

    @Override
    public List<CommandEvent> events(
            CreateTicketCommand command, AppendResult result, CommandContext ctx) {
        return List.of(ctx.event("ticket.created", result.appended().fields()));
    }
}
