package cn.byteforce.seedj.transit;

public interface TransitMac {
    MacResult hmac(HmacRequest request);

    boolean verifyHmac(VerifyHmacRequest request);
}
