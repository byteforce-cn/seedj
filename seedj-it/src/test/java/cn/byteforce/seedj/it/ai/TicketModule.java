package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.module.ObjectModuleConfigurer;
import cn.byteforce.seedj.module.ObjectModules;

/**
 * pilot 工单对象的唯一读侧声明：schema、读权限与两个命令类型。
 *
 * <p>字段只在 {@link Ticket} 声明一次；这里只声明「谁能读、哪些命令能写」。两条命令分别对应 T0 要求覆盖的两档 动作分类（{@code ticket.create} =
 * {@code COMPENSABLE}、{@code ticket.comment} = {@code REVERSIBLE}），动作分类本身不在这里写死：它是 {@code
 * seedj.ai.tools[]} 的配置事实，决策面与守卫都从同一份 配置取。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class TicketModule implements ObjectModuleConfigurer {

    private final CreateTicketHandler create;

    private final CommentTicketHandler comment;

    private final RefundTicketHandler refund;

    /**
     * 构造模块声明。
     *
     * @param create 创建处理器
     * @param comment 评论处理器
     * @param refund 退款处理器
     */
    public TicketModule(
            CreateTicketHandler create,
            CommentTicketHandler comment,
            RefundTicketHandler refund) {
        this.create = create;
        this.comment = comment;
        this.refund = refund;
    }

    @Override
    public void configure(ObjectModules modules) {
        modules.object(Ticket.schema())
                .readPermission("ticket.read")
                .command("ticket.create", CreateTicketCommand.class)
                .permission("ticket.create")
                .creates()
                .handler(create)
                .emits("ticket.created")
                .command("ticket.comment", CommentTicketCommand.class)
                .permission("ticket.comment")
                .handler(comment)
                .emits("ticket.commented")
                .commandField("ticket_id")
                .commandField("note")
                .commandField("expected_version")
                .command("ticket.refund", RefundTicketCommand.class)
                .permission("ticket.refund")
                .handler(refund)
                .emits("ticket.refunded")
                .commandField("ticket_id")
                .commandField("amount_cents")
                .commandField("expected_version");
    }
}
