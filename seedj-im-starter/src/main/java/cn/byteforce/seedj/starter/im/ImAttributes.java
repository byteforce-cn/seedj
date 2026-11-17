package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.security.Caller;
import io.netty.util.AttributeKey;

/** 连接级属性键。 */
final class ImAttributes {

    /** 握手成功后写入的调用方身份。 */
    static final AttributeKey<Caller> CALLER = AttributeKey.valueOf("seedj.im.caller");

    /** 握手成功后写入的设备标识（客户端提供，或连接级生成）。 */
    static final AttributeKey<String> DEVICE_ID = AttributeKey.valueOf("seedj.im.device");

    private ImAttributes() {}
}
