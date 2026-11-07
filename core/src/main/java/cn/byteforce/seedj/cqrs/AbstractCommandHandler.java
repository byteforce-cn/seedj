package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.utils.FieldNames;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.TemporalRow;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
        return ctx.current()
                .orElseThrow(() -> reject(ContractErrorCode.OBJECT_NOT_FOUND, "object not found"));
    }

    /**
     * 把命令 record 中与 schema 同名的组件映射为持久化字段。
     *
     * <p>这是「加字段只改 schema + 命令 record」的落点：组件名按 snake_case 映射， null 跳过，不在 schema 中的组件（目标
     * id、原始证件号等命令专用字段）不进入对象。 返回的 map 可变，handler 可以继续补工号、状态、派生散列等计算字段。
     *
     * @param ctx 上下文（提供 schema）
     * @param command 命令 record
     * @return 保持 record 组件顺序的字段 map
     */
    protected final Map<String, Object> schemaFields(CommandContext ctx, C command) {
        Objects.requireNonNull(ctx, "ctx");
        if (command == null || !command.getClass().isRecord()) {
            throw new IllegalArgumentException(
                    "command must be a record to derive schema fields: "
                            + (command == null ? "null" : command.getClass().getName()));
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (RecordComponent component : command.getClass().getRecordComponents()) {
            String field = FieldNames.snakeCase(component.getName());
            if (ctx.schema().field(field).isEmpty()) {
                continue;
            }
            Object value;
            try {
                Method accessor = component.getAccessor();
                if (!accessor.canAccess(command)) {
                    accessor.setAccessible(true);
                }
                value = accessor.invoke(command);
            } catch (InvocationTargetException error) {
                if (error.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(
                        "cannot read command component: " + component.getName(), error);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(
                        "cannot read command component: " + component.getName(), error);
            }
            if (value != null) {
                fields.put(field, value);
            }
        }
        return fields;
    }

    /**
     * 合并非空变更；未知字段失败。
     *
     * @param ctx 上下文
     * @param changes 变更
     * @return 合并后的字段
     */
    protected final Map<String, Object> merge(CommandContext ctx, Map<String, Object> changes) {
        Map<String, Object> merged = new LinkedHashMap<>(requireCurrent(ctx).fields());
        changes.forEach(
                (field, value) -> {
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
     * @param ctx 上下文
     * @param validFrom 业务生效时间
     * @param fields 字段
     * @return 追加请求
     */
    protected final AppendRequest create(
            CommandContext ctx, Instant validFrom, Map<String, Object> fields) {
        return AppendRequest.create(
                ctx.schema().objectType(),
                ctx.targetId(),
                validFrom,
                null,
                ctx.actor(),
                fields,
                ctx.expectedVersion());
    }

    /**
     * 构造更正追加。
     *
     * @param ctx 上下文
     * @param fields 字段
     * @return 追加请求
     */
    protected final AppendRequest correct(CommandContext ctx, Map<String, Object> fields) {
        return AppendRequest.correction(
                ctx.schema().objectType(),
                ctx.targetId(),
                ctx.now(),
                null,
                ctx.actor(),
                fields,
                ctx.expectedVersion());
    }

    /**
     * 构造拒绝错误。
     *
     * @param code 错误码
     * @param reason 原因
     * @return 契约错误
     */
    protected final ContractException reject(ContractErrorCode code, String reason) {
        return new ContractException(code, reason);
    }
}
