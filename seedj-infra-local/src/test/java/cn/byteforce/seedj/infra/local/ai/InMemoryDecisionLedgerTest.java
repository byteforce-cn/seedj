package cn.byteforce.seedj.infra.local.ai;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.ai.spi.DecisionLedgerContractTest;

/**
 * 内存决策账本跑公共契约套件：与 PostgreSQL 账本语义一致的那批行为。
 *
 * <p>账本实例在字段里只建一次，{@link #reset()} 调包的 {@code clear()} 清空全部分区，让每个用例从空账本开始。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class InMemoryDecisionLedgerTest extends DecisionLedgerContractTest {

    private final InMemoryDecisionLedger ledger = new InMemoryDecisionLedger();

    @Override
    protected DecisionLedger ledger() {
        return ledger;
    }

    @Override
    protected void reset() {
        ledger.clear();
    }
}
