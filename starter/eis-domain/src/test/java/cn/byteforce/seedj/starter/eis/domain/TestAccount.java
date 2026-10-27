package cn.byteforce.seedj.starter.eis.domain;

import java.util.List;

/**
 * 测试用聚合：只通过 {@code raise} 改状态，只通过 {@code apply} 落状态。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class TestAccount extends AggregateRoot<Long> implements EventApplier<AccountEvent> {

    private String owner;

    private long balance;

    private TestAccount(Long id) {
        super(id);
    }

    private TestAccount(Long id, long version) {
        super(id, version);
    }

    static TestAccount open(Long id, String owner) {
        TestAccount account = new TestAccount(id);
        account.raise(new AccountEvent.Opened(owner));
        return account;
    }

    void deposit(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        raise(new AccountEvent.Deposited(amount));
    }

    static TestAccount rehydrate(Long id, long version, List<DomainEvent> history) {
        TestAccount account = new TestAccount(id, version);
        history.forEach(account::replay);
        return account;
    }

    String owner() {
        return owner;
    }

    long balance() {
        return balance;
    }

    protected void apply(AccountEvent.Opened event) {
        this.owner = event.owner();
    }

    protected void apply(AccountEvent.Deposited event) {
        this.balance += event.amount();
    }
}
