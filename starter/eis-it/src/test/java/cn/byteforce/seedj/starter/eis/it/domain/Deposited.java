package cn.byteforce.seedj.starter.eis.it.domain;

import cn.byteforce.seedj.starter.eis.domain.DomainEvent;
import cn.byteforce.seedj.starter.eis.domain.EventType;

/**
 * 集成测试事件：当前 schema 版本 2（long 金额 + 币种）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@EventType(value = "it.account.deposited", version = 2)
public final class Deposited implements DomainEvent {

    private long amount;

    private String currency;

    /**
     * 供 Jackson 反序列化使用。
     */
    public Deposited() {
    }

    /**
     * 构造事件。
     *
     * @param amount   金额
     * @param currency 币种
     */
    public Deposited(long amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    /**
     * 返回金额。
     *
     * @return 金额
     */
    public long getAmount() {
        return amount;
    }

    /**
     * 返回币种。
     *
     * @return 币种
     */
    public String getCurrency() {
        return currency;
    }
}
