package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.TemporalRow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令处理模板：只提供动作，不替业务做决定。
 *
 * @param <C> 命令载荷类型
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class AbstractCommandHandler<C> implements CommandHandler<C> {

    /**
     * 当前行，缺失即 404。
     *
     * @param ctx 上下文
     * @return 当前行
     */
    protected final TemporalRow requireCurrent(CommandContext ctx) {
        return ctx.current().orElseThrow(() -> reject(ContractErrorCode.OBJECT_NOT_FOUND,
                "object not found"));
    }

    /**
     * 合并非空变更；未知字段失败。
     *
     * @param ctx     上下文
     * @param changes 变更
     * @return 合并后的字段
     */
    protected final Map<String, Object> merge(CommandContext ctx,
                                              Map<String, Object> changes) {
        Map<String, Object> merged = new LinkedHashMap<>(requireCurrent(ctx).fields());
        changes.forEach((field, value) -> {
            ctx.schema().requireField(field);
            if (value != null) {
                merged.put(field, value);
            }
        });
        return merged;
    }

    /**
     * 构造创建追加。
     *
     * @param ctx       上下文
     * @param validFrom 业务生效时间
     * @param fields    字段
     * @return 追加请求
     */
    protected final AppendRequest create(CommandContext ctx, Instant validFrom,
                                         Map<String, Object> fields) {
        return AppendRequest.create(ctx.schema().objectType(), ctx.targetId(), validFrom, null,
                ctx.actor(), fields);
    }

    /**
     * 构造更正追加。
     *
     * @param ctx    上下文
     * @param fields 字段
     * @return 追加请求
     */
    protected final AppendRequest correct(CommandContext ctx, Map<String, Object> fields) {
        return AppendRequest.correction(ctx.schema().objectType(), ctx.targetId(), ctx.now(),
                null, ctx.actor(), fields);
    }

    /**
     * 构造拒绝错误。
     *
     * @param code   错误码
     * @param reason 原因
     * @return 契约错误
     */
    protected final ContractException reject(ContractErrorCode code, String reason) {
        return new ContractException(code, reason);
    }
}
