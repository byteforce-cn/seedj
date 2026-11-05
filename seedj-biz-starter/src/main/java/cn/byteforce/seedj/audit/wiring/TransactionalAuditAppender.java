package cn.byteforce.seedj.audit.wiring;

import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditAppender;
import cn.byteforce.seedj.audit.AuditErrorCode;
import cn.byteforce.seedj.audit.AuditException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * 生产装配强制的事务契约：审计只在调用方事务内追加。
 *
 * <p>没有活动事务时抛 {@link AuditErrorCode#TX_REQUIRED}，而不是悄悄单开一个事务——
 * 单开事务会让“命令回滚、审计已提交”成为可能。</p>
 */
public final class TransactionalAuditAppender implements AuditAppender<String> {

    private final AuditAppender<String> delegate;

    public TransactionalAuditAppender(AuditAppender<String> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void append(AuditAppendRequest<String> request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AuditException(AuditErrorCode.TX_REQUIRED,
                    "audit append must be called inside an active transaction");
        }
        delegate.append(request);
    }
}
