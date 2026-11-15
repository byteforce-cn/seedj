package cn.byteforce.seedj.infra.coord;

import cn.byteforce.seedj.eventbus.spi.DeliveryFailure;

/** 死信记录：带 group 归属与失败原因，供 {@code deadLetters} 直接返回。 */
record StoredDeadLetter(
        String id,
        String topic,
        String group,
        StoredMessage message,
        DeliveryFailure failure,
        long deadLetteredAtMs) {}
