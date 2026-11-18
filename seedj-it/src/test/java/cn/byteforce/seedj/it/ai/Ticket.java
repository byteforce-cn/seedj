package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.codegen.SeedjField;
import cn.byteforce.seedj.codegen.SeedjObject;
import cn.byteforce.seedj.domain.ObjectSchema;
import java.util.UUID;

/**
 * T0 pilot 的业务对象：字段的唯一来源（schema、读视图与建表 DDL 都由 {@code seedj-codegen} 从这里生成）。
 *
 * <p>字段集合刻意很小，但每个字段都背着一条断言：{@code status} 证明读回的是业务字段，{@code assignee} 证明 UUID 列能 往返，{@code
 * internalNote} 是 {@code writeOnly}——守卫按<b>未脱敏原文</b>算 {@code args_hash}（所以载荷里有它也必须
 * 202），读路径却永远不返回它。{@code lastComment}/{@code commentCount} 是第二条命令（可逆动作）的落点。
 *
 * @param title 标题（必填）
 * @param status 状态；创建时缺省 {@code open}
 * @param internalNote 内部备注（写入专用，读路径排除）
 * @param assignee 受理人；可空
 * @param lastComment 最近一条评论
 * @param commentCount 评论条数
 * @author ByteForce
 * @since 1.0.0
 */
@SeedjObject(objectType = "ticket", table = "pilot_ticket", view = true)
public record Ticket(
        @SeedjField(required = true, minLength = 1) String title,
        @SeedjField(
                        required = true,
                        enumValues = {"open", "closed"})
                String status,
        @SeedjField(writeOnly = true) String internalNote,
        @SeedjField(indexed = true) UUID assignee,
        @SeedjField String lastComment,
        @SeedjField Integer commentCount) {

    /**
     * 生成的 schema。
     *
     * @return 对象 schema
     */
    public static ObjectSchema schema() {
        return TicketSchema.schema();
    }
}
