package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.security.Caller;
import io.netty.util.AttributeKey;

/** 连接级属性键。 */
final class ImAttributes {

    /** 握手成功后写入的调用方身份。 */
    static final AttributeKey<Caller> CALLER = AttributeKey.valueOf("seedj.im.caller");

    private ImAttributes() {}
}
