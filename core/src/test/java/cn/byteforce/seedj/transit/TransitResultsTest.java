package cn.byteforce.seedj.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.ErrorCategory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 结果与密钥值对象：构造校验、不可变语义与错误码分类。 */
class TransitResultsTest {

    @Test
    @DisplayName("Ciphertext：密文必填，透传版本与 context 摘要")
    void ciphertext() {
        Ciphertext value = new Ciphertext("vault:v1:abc", 1, "hash");
        assertThat(value.getValue()).isEqualTo("vault:v1:abc");
        assertThat(value.getKeyVersion()).isEqualTo(1);
        assertThat(value.getContextHash()).isEqualTo("hash");
        assertThat(value.toString()).isEqualTo("Ciphertext{keyVersion=1}");

        assertThatThrownBy(() -> new Ciphertext(null, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Signature / MacResult：算法必须回填")
    void signatureAndMacResult() {
        Signature signature = new Signature("c2ln", SignatureAlgorithm.SHA256_WITH_ECDSA, 2);
        assertThat(signature.getValue()).isEqualTo("c2ln");
        assertThat(signature.getAlgorithm()).isEqualTo(SignatureAlgorithm.SHA256_WITH_ECDSA);
        assertThat(signature.getKeyVersion()).isEqualTo(2);
        assertThat(signature.toString())
                .isEqualTo("Signature{algorithm=SHA256_WITH_ECDSA, keyVersion=2}");

        assertThatThrownBy(() -> new Signature(null, SignatureAlgorithm.ED25519, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Signature("c2ln", null, null))
                .isInstanceOf(IllegalArgumentException.class);

        MacResult mac = new MacResult("bWFj", HmacAlgorithm.SHA384, 5);
        assertThat(mac.getValue()).isEqualTo("bWFj");
        assertThat(mac.getAlgorithm()).isEqualTo(HmacAlgorithm.SHA384);
        assertThat(mac.getKeyVersion()).isEqualTo(5);
        assertThat(mac.toString()).isEqualTo("MacResult{algorithm=SHA384, keyVersion=5}");

        assertThatThrownBy(() -> new MacResult(null, HmacAlgorithm.SHA256, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MacResult("bWFj", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("KeyInfo：全部只读视图可取值")
    void keyInfo() {
        KeyInfo info =
                new KeyInfo("k1", KeyType.AES256_GCM96, 4, 2, 3, false, true, false, true, false);

        assertThat(info.getName()).isEqualTo("k1");
        assertThat(info.getType()).isEqualTo(KeyType.AES256_GCM96);
        assertThat(info.getLatestVersion()).isEqualTo(4);
        assertThat(info.getMinDecryptionVersion()).isEqualTo(2);
        assertThat(info.getMinEncryptionVersion()).isEqualTo(3);
        assertThat(info.isDerived()).isFalse();
        assertThat(info.isExportable()).isTrue();
        assertThat(info.isDeletionAllowed()).isFalse();
        assertThat(info.isConvergentEncryption()).isTrue();
        assertThat(info.isAllowPlaintextBackup()).isFalse();
        assertThat(info.toString()).isEqualTo("KeyInfo{name='k1', type=AES256_GCM96}");
    }

    @Test
    @DisplayName("ExportedKey：密钥映射不可变")
    void exportedKey() {
        ExportedKey exported = new ExportedKey(KeyType.RSA_2048, true, Map.of(1, "pem"));
        assertThat(exported.getKeyType()).isEqualTo(KeyType.RSA_2048);
        assertThat(exported.isContainsPrivateKey()).isTrue();
        assertThat(exported.getKeys()).containsEntry(1, "pem");
        assertThat(exported.toString())
                .isEqualTo("ExportedKey{keyType=RSA_2048, containsPrivateKey=true}");
    }

    @Test
    @DisplayName("DataKey：明文只在销毁前可用，销毁后不可恢复")
    void dataKey() {
        byte[] plaintext = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        Ciphertext ciphertext = new Ciphertext("vault:v1:wrapped", 1, null);
        DataKey dataKey = new DataKey(plaintext, ciphertext);

        assertThat(dataKey.hasPlaintext()).isTrue();
        assertThat(dataKey.getCiphertextKey()).isSameAs(ciphertext);
        plaintext[0] = 'X';
        assertThat(dataKey.usePlaintextKey()[0]).isEqualTo((byte) '0');

        dataKey.destroy();

        assertThat(dataKey.hasPlaintext()).isFalse();
        assertThatThrownBy(dataKey::usePlaintextKey).isInstanceOf(IllegalStateException.class);
        dataKey.destroy();
        assertThat(dataKey.hasPlaintext()).isFalse();
    }

    @Test
    @DisplayName("DataKey：无明文时 usePlaintextKey 立即失败")
    void dataKeyWithoutPlaintext() {
        DataKey dataKey = new DataKey(null, new Ciphertext("vault:v1:wrapped", null, null));
        assertThat(dataKey.hasPlaintext()).isFalse();
        assertThatThrownBy(dataKey::usePlaintextKey).isInstanceOf(IllegalStateException.class);
        assertThat(dataKey.getCiphertextKey().getValue()).isEqualTo("vault:v1:wrapped");
    }

    @Test
    @DisplayName("BatchResult：成功与失败互斥")
    void batchResult() {
        BatchResult<Ciphertext> ok =
                new BatchResult<>("ref-1", new Ciphertext("vault:v1:a", 1, null), null, null);
        assertThat(ok.getReference()).isEqualTo("ref-1");
        assertThat(ok.getValue().getValue()).isEqualTo("vault:v1:a");
        assertThat(ok.getErrorCode()).isNull();
        assertThat(ok.getErrorMessage()).isNull();
        assertThat(ok.toString()).isEqualTo("BatchResult{reference='ref-1', errorCode=null}");

        BatchResult<Ciphertext> failed =
                new BatchResult<>(
                        "ref-2", null, TransitErrorCode.INVALID_CIPHERTEXT, "bad ciphertext");
        assertThat(failed.getValue()).isNull();
        assertThat(failed.getErrorCode()).isEqualTo(TransitErrorCode.INVALID_CIPHERTEXT);
        assertThat(failed.getErrorMessage()).isEqualTo("bad ciphertext");

        assertThatThrownBy(() -> new BatchResult<>("ref-3", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new BatchResult<>(
                                        "ref-3", new Ciphertext("v", 1, null), null, "unexpected"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new BatchResult<>(
                                        "ref-3",
                                        new Ciphertext("v", 1, null),
                                        TransitErrorCode.KEY_NOT_FOUND,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchResult<>("", null, TransitErrorCode.KEY_NOT_FOUND, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TransitException：携带错误码、类别与可重试标记")
    void transitException() {
        TransitException fromCode =
                new TransitException(TransitErrorCode.KEY_NOT_FOUND, "missing key", null);
        assertThat(fromCode.getErrorCode()).isEqualTo(TransitErrorCode.KEY_NOT_FOUND);
        assertThat(fromCode.getMessage()).isEqualTo("missing key");
        assertThat(fromCode.category()).isEqualTo(ErrorCategory.CLIENT_ERROR);
        assertThat(fromCode.isRetryable()).isFalse();

        IllegalStateException cause = new IllegalStateException("boom");
        TransitException retryable =
                new TransitException(
                        TransitErrorCode.BACKEND_UNAVAILABLE, "backend down", true, cause);
        assertThat(retryable.getErrorCode()).isEqualTo(TransitErrorCode.BACKEND_UNAVAILABLE);
        assertThat(retryable.getCause()).isSameAs(cause);
        assertThat(retryable.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("TransitErrorCode：类别与默认可重试")
    void transitErrorCode() {
        assertThat(TransitErrorCode.UNSUPPORTED_OPERATION.category())
                .isEqualTo(ErrorCategory.CONFIG_ERROR);
        assertThat(TransitErrorCode.RETRYABLE_NETWORK_ERROR.category())
                .isEqualTo(ErrorCategory.BACKEND_ERROR);
        assertThat(TransitErrorCode.RETRYABLE_NETWORK_ERROR.defaultRetryable()).isTrue();
        assertThat(TransitErrorCode.BATCH_SIZE_EXCEEDED.category())
                .isEqualTo(ErrorCategory.LIMIT_EXCEEDED);
        assertThat(TransitErrorCode.BATCH_SIZE_EXCEEDED.defaultRetryable()).isFalse();
    }

    @Test
    @DisplayName("TransitProvider：close 默认幂等且不抛")
    void providerCloseIsNoOp() {
        TransitProvider provider =
                new TransitProvider() {
                    @Override
                    public TransitCrypto crypto() {
                        return null;
                    }

                    @Override
                    public TransitSigner signer() {
                        return null;
                    }

                    @Override
                    public TransitMac mac() {
                        return null;
                    }

                    @Override
                    public TransitKeyAdmin keyAdmin() {
                        return null;
                    }

                    @Override
                    public boolean supports(KeyType keyType, TransitOperation operation) {
                        return false;
                    }
                };

        provider.close();
        provider.close();

        assertThat(provider.supports(KeyType.HMAC, TransitOperation.HMAC)).isFalse();
        assertThat(provider.crypto()).isNull();
        assertThat(provider.signer()).isNull();
        assertThat(provider.mac()).isNull();
        assertThat(provider.keyAdmin()).isNull();
    }

    @Test
    @DisplayName("Validation：reference 与版本号校验")
    void validationHelpers() {
        assertThat(Validation.requireReference("ref")).isEqualTo("ref");
        assertThatThrownBy(() -> Validation.requireReference(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Validation.requireReference(" "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Validation.requirePositiveVersion(null)).isNull();
        assertThat(Validation.requirePositiveVersion(1)).isEqualTo(1);
        assertThatThrownBy(() -> Validation.requirePositiveVersion(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Validation.requirePositiveVersion(-2))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Validation.requireBase64NonEmpty(null, "signature"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
        assertThatThrownBy(() -> Validation.requireBase64NonEmpty("", "hmac"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hmac");
        assertThat(Validation.requireBase64NonEmpty("aGVsbG8=", "hmac")).isEqualTo("aGVsbG8=");

        assertThatThrownBy(() -> Base64Validator.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base64Validator.validate(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Validation.requireBatchList(List.of("a"))).containsExactly("a");
    }
}
