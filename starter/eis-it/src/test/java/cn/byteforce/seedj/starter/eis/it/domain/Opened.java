package cn.byteforce.seedj.starter.eis.it.domain;

import cn.byteforce.seedj.starter.eis.domain.DomainEvent;
import cn.byteforce.seedj.starter.eis.domain.EventType;

/**
 * 集成测试事件：账户开立（v1）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@EventType("it.account.opened")
public final class Opened implements DomainEvent {

    private String owner;

    /**
     * 供 Jackson 反序列化使用。
     */
    public Opened() {
    }

    /**
     * 构造事件。
     *
     * @param owner 户主
     */
    public Opened(String owner) {
        this.owner = owner;
    }

    /**
     * 返回户主。
     *
     * @return 户主
     */
    public String getOwner() {
        return owner;
    }
}
