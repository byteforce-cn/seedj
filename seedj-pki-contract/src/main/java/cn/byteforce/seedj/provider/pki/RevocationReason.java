package cn.byteforce.seedj.provider.pki;

/** RFC 5280 CRLReason。数值写进 CRL 条目，名称写进日志与审计。 */
public enum RevocationReason {
    UNSPECIFIED(0),
    KEY_COMPROMISE(1),
    CA_COMPROMISE(2),
    AFFILIATION_CHANGED(3),
    SUPERSEDED(4),
    CESSATION_OF_OPERATION(5),
    CERTIFICATE_HOLD(6),
    PRIVILEGE_WITHDRAWN(8),
    AA_COMPROMISE(10);

    private final int rfcCode;

    RevocationReason(int rfcCode) {
        this.rfcCode = rfcCode;
    }

    public int rfcCode() {
        return rfcCode;
    }

    public static RevocationReason fromRfcCode(int rfcCode) {
        for (RevocationReason reason : values()) {
            if (reason.rfcCode == rfcCode) {
                return reason;
            }
        }
        return UNSPECIFIED;
    }
}
