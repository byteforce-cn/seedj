package cn.byteforce.seedj.transit;

public enum TransitOperation {
    ENCRYPT,
    DECRYPT,
    REWRAP,
    SIGN,
    VERIFY,
    HMAC,
    VERIFY_HMAC,
    GENERATE_DATA_KEY,
    EXPORT,
    ROTATE,
    CONFIGURE,
    DELETE
}
