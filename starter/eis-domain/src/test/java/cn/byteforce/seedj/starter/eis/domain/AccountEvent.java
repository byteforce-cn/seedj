package cn.byteforce.seedj.starter.eis.domain;

import java.util.Objects;

/**
 * 测试用事件族：sealed 父类型 + 不可变事件类（禁 record）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public sealed interface AccountEvent extends DomainEvent permits AccountEvent.Opened, AccountEvent.Deposited,
        AccountEvent.Frozen, AccountEvent.Ignored {

    /**
     * 账户开立。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    final class Opened implements AccountEvent {

        private final String owner;

        public Opened(String owner) {
            this.owner = owner;
        }

        String owner() {
            return owner;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Opened opened && Objects.equals(owner, opened.owner);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(owner);
        }

        @Override
        public String toString() {
            return "Opened[owner=" + owner + "]";
        }
    }

    /**
     * 存入。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    final class Deposited implements AccountEvent {

        private final long amount;

        public Deposited(long amount) {
            this.amount = amount;
        }

        long amount() {
            return amount;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Deposited deposited && amount == deposited.amount;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(amount);
        }

        @Override
        public String toString() {
            return "Deposited[amount=" + amount + "]";
        }
    }

    /**
     * 冻结（故意不在 {@link TestAccount} 中处理，用于验证缺失处理器报错）。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    final class Frozen implements AccountEvent {
    }

    /**
     * 被 {@link TestAccount} 忽略的事件（用于验证重放一致性断言）。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    final class Ignored implements AccountEvent {
    }
}
