package cn.byteforce.seedj.starter.data.mybatis;

import cn.byteforce.seedj.core.context.RequestContext;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 审计字段填充器：为 {@code BaseEntity}（及其子类）的创建/更新人、创建/更新时间自动填充。
 *
 * <p>仅填充带对应 {@code FieldFill} 注解的字段（strict 模式）；
 * 操作人取 {@code RequestContext.username()}，上下文缺失时留空。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private final boolean useRequestContext;

    /**
     * 构造填充器。
     *
     * @param useRequestContext 是否从请求上下文读取操作人
     */
    public AuditMetaObjectHandler(boolean useRequestContext) {
        this.useRequestContext = useRequestContext;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        if (useRequestContext) {
            RequestContext.username().ifPresent(username -> {
                strictInsertFill(metaObject, "createBy", String.class, username);
                strictInsertFill(metaObject, "updateBy", String.class, username);
            });
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        if (useRequestContext) {
            RequestContext.username()
                    .ifPresent(username -> strictUpdateFill(metaObject, "updateBy", String.class, username));
        }
    }
}
