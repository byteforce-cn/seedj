package cn.byteforce.seedj.provider.pki;

/** provider 已关闭。除 {@code close()} 外的调用在关闭后都会抛本异常。 */
public class PkiClosedException extends PkiException {

    public PkiClosedException(String provider) {
        super(PkiErrorCode.PROVIDER_CLOSED, provider + " pki provider is closed");
    }
}
