package cn.byteforce.seedj.it.fixture.hr;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;

/** HR 侧消费的事件类型。字段与 IM 发布的 JSON payload 对齐。 */
@EventType("it.consume")
public record HrOrderPlaced(String orderId) implements DomainEvent {}
