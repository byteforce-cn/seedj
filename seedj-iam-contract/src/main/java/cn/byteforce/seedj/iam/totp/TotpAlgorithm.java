package cn.byteforce.seedj.iam.totp;

public enum TotpAlgorithm {
    SHA1("HmacSHA1", "SHA1"),
    SHA256("HmacSHA256", "SHA256"),
    SHA512("HmacSHA512", "SHA512");

    private final String hmacName;
    private final String otpauthName;

    TotpAlgorithm(String hmacName, String otpauthName) {
        this.hmacName = hmacName;
        this.otpauthName = otpauthName;
    }

    public String hmacName() {
        return hmacName;
    }

    public String otpauthName() {
        return otpauthName;
    }
}
