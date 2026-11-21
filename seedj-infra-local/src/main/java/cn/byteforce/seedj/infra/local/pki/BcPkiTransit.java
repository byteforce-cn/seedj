package cn.byteforce.seedj.infra.local.pki;

import cn.byteforce.seedj.provider.pki.PkiErrorCode;
import cn.byteforce.seedj.provider.pki.PkiException;
import cn.byteforce.seedj.transit.SignRequest;
import cn.byteforce.seedj.transit.Signature;
import cn.byteforce.seedj.transit.SignatureAlgorithm;
import cn.byteforce.seedj.transit.TransitSigner;
import cn.byteforce.seedj.transit.VerifyRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * 把 PKI 契约的签名参数翻译成本地 Transit 的 {@link SignatureAlgorithm}。
 *
 * <p>本地 Transit 只签 UTF-8 输入，不支持 prehashed 与 RSA-PSS；这些组合显式报 {@link
 * PkiErrorCode#UNSUPPORTED}，不静默降级成另一种算法。
 */
final class BcPkiTransit {

    private BcPkiTransit() {}

    static byte[] sign(
            TransitSigner signer,
            String keyName,
            String hashAlgorithm,
            boolean prehashed,
            String signatureAlgorithm,
            byte[] data) {
        require(signer, keyName, prehashed, data);
        Signature signature =
                signer.sign(
                        new SignRequest(
                                keyName,
                                new String(data, StandardCharsets.UTF_8),
                                signatureAlgorithm(hashAlgorithm, signatureAlgorithm),
                                null));
        return Base64.getDecoder().decode(signature.getValue());
    }

    static boolean verify(
            TransitSigner signer,
            String keyName,
            String hashAlgorithm,
            boolean prehashed,
            String signatureAlgorithm,
            byte[] data,
            byte[] signature) {
        require(signer, keyName, prehashed, data);
        if (signature == null || signature.length == 0) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "signature 不能为空");
        }
        return signer.verify(
                new VerifyRequest(
                        keyName,
                        new String(data, StandardCharsets.UTF_8),
                        Base64.getEncoder().encodeToString(signature),
                        signatureAlgorithm(hashAlgorithm, signatureAlgorithm),
                        null));
    }

    private static void require(
            TransitSigner signer, String keyName, boolean prehashed, byte[] data) {
        if (signer == null) {
            throw new PkiException(
                    PkiErrorCode.UNSUPPORTED,
                    "local PKI has no Transit signer wired; provide a TransitProvider to sign/verify");
        }
        if (keyName == null || keyName.isBlank()) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "keyName 不能为空");
        }
        if (data == null) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "data 不能为空");
        }
        if (prehashed) {
            throw new PkiException(
                    PkiErrorCode.UNSUPPORTED,
                    "local Transit signs raw UTF-8 input; prehashed=true is only available on coord");
        }
    }

    private static SignatureAlgorithm signatureAlgorithm(
            String hashAlgorithm, String signatureAlgorithm) {
        String hash = normalize(hashAlgorithm == null ? "sha256" : hashAlgorithm);
        String signature =
                normalize(signatureAlgorithm == null ? "pkcs1v15" : signatureAlgorithm);
        if (signature.contains("pss")) {
            throw new PkiException(
                    PkiErrorCode.UNSUPPORTED,
                    "local Transit does not implement RSA-PSS; use pkcs1v15 or coord");
        }
        if (signature.contains("ed25519")) {
            return SignatureAlgorithm.ED25519;
        }
        String digest =
                switch (hash) {
                    case "sha384", "sha2384" -> "SHA384";
                    case "sha512", "sha2512" -> "SHA512";
                    default -> "SHA256";
                };
        if (signature.contains("ecdsa")) {
            return SignatureAlgorithm.valueOf(digest + "_WITH_ECDSA");
        }
        return SignatureAlgorithm.valueOf(digest + "_WITH_RSA");
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
