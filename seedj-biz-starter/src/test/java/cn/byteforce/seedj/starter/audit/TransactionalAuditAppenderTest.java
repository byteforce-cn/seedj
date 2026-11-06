package cn.byteforce.seedj.starter.audit;

import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditErrorCode;
import cn.byteforce.seedj.audit.AuditException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionalAuditAppenderTest {

    private final List<AuditAppendRequest<String>> appended = new ArrayList<>();

    private final TransactionalAuditAppender appender = new TransactionalAuditAppender(
            appended::add);

    private final AuditAppendRequest<String> request =
            new AuditAppendRequest<>("employee.onboarded", "alice", "employee", "e-1", null);

    @Test
    @DisplayName("没有活动事务时拒绝写入")
    void requiresActiveTransaction() {
        assertThatThrownBy(() -> appender.append(request))
                .isInstanceOf(AuditException.class)
                .extracting(e -> ((AuditException) e).getErrorCode())
                .isEqualTo(AuditErrorCode.TX_REQUIRED);
        assertThat(appended).isEmpty();
    }

    @Test
    @DisplayName("有活动事务时委派写入")
    void delegatesInsideTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            appender.append(request);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(appended).containsExactly(request);
    }
}
