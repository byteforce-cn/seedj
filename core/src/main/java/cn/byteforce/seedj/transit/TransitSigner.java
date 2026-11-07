package cn.byteforce.seedj.transit;

public interface TransitSigner {
    Signature sign(SignRequest request);

    boolean verify(VerifyRequest request);
}
