package cn.byteforce.seedj.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 请求模型的构造语义：校验规则、默认归一化与访问器。 */
class TransitRequestsTest {

    @Test
    @DisplayName("EncryptRequest：明文必填、context 归一化、版本必须为正")
    void encryptRequest() {
        EncryptRequest full = new EncryptRequest("k1", "secret", "aGVsbG8=", 2);
        assertThat(full.getKeyName()).isEqualTo("k1");
        assertThat(full.getPlaintext()).isEqualTo("secret");
        assertThat(full.getContext()).isEqualTo("aGVsbG8=");
        assertThat(full.getKeyVersion()).isEqualTo(2);
        assertThat(full.toString()).isEqualTo("EncryptRequest{keyName='k1'}");

        EncryptRequest minimal = EncryptRequest.of("k1", "secret");
        assertThat(minimal.getContext()).isNull();
        assertThat(minimal.getKeyVersion()).isNull();

        assertThatThrownBy(() -> EncryptRequest.of("k1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EncryptRequest.of(" k1", "secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EncryptRequest("k1", "secret", " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EncryptRequest("k1", "secret", null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DecryptRequest / RewrapRequest：密文必填，context 归一化")
    void decryptAndRewrapRequest() {
        DecryptRequest decrypt = DecryptRequest.of("k1", "cipher");
        assertThat(decrypt.getKeyName()).isEqualTo("k1");
        assertThat(decrypt.getCiphertext()).isEqualTo("cipher");
        assertThat(decrypt.getContext()).isNull();
        assertThat(decrypt.toString()).isEqualTo("DecryptRequest{keyName='k1'}");

        DecryptRequest withContext = new DecryptRequest("k1", "cipher", "aGVsbG8=");
        assertThat(withContext.getContext()).isEqualTo("aGVsbG8=");
        assertThatThrownBy(() -> DecryptRequest.of("k1", null))
                .isInstanceOf(IllegalArgumentException.class);

        RewrapRequest rewrap = new RewrapRequest("k1", "cipher", "");
        assertThat(rewrap.getKeyName()).isEqualTo("k1");
        assertThat(rewrap.getCiphertext()).isEqualTo("cipher");
        assertThat(rewrap.getContext()).isNull();
        assertThatThrownBy(() -> new RewrapRequest("k1", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SignRequest / VerifyRequest：data 必填，signature 必须是严格 Base64")
    void signAndVerifyRequest() {
        SignRequest sign = new SignRequest("k1", "payload", SignatureAlgorithm.ED25519, 3);
        assertThat(sign.getKeyName()).isEqualTo("k1");
        assertThat(sign.getData()).isEqualTo("payload");
        assertThat(sign.getAlgorithm()).isEqualTo(SignatureAlgorithm.ED25519);
        assertThat(sign.getKeyVersion()).isEqualTo(3);
        assertThat(sign.toString()).isEqualTo("SignRequest{keyName='k1'}");
        assertThatThrownBy(() -> new SignRequest("k1", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        VerifyRequest verify =
                new VerifyRequest(
                        "k1", "payload", "aGVsbG8=", SignatureAlgorithm.SHA256_WITH_RSA, 1);
        assertThat(verify.getSignature()).isEqualTo("aGVsbG8=");
        assertThat(verify.getAlgorithm()).isEqualTo(SignatureAlgorithm.SHA256_WITH_RSA);
        assertThat(verify.getData()).isEqualTo("payload");
        assertThat(verify.getKeyName()).isEqualTo("k1");
        assertThat(verify.getKeyVersion()).isEqualTo(1);

        assertThatThrownBy(() -> new VerifyRequest("k1", "payload", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifyRequest("k1", "payload", "", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifyRequest("k1", "payload", "not-base64", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifyRequest("k1", null, "aGVsbG8=", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("HmacRequest / VerifyHmacRequest：hmac 必须是严格 Base64")
    void hmacRequests() {
        HmacRequest hmac = new HmacRequest("k1", "payload", HmacAlgorithm.SHA512, 4);
        assertThat(hmac.getKeyName()).isEqualTo("k1");
        assertThat(hmac.getData()).isEqualTo("payload");
        assertThat(hmac.getAlgorithm()).isEqualTo(HmacAlgorithm.SHA512);
        assertThat(hmac.getKeyVersion()).isEqualTo(4);
        assertThatThrownBy(() -> new HmacRequest("k1", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        VerifyHmacRequest verify =
                new VerifyHmacRequest("k1", "payload", "aGVsbG8=", HmacAlgorithm.SHA256, 1);
        assertThat(verify.getHmac()).isEqualTo("aGVsbG8=");
        assertThat(verify.getAlgorithm()).isEqualTo(HmacAlgorithm.SHA256);
        assertThat(verify.getData()).isEqualTo("payload");
        assertThat(verify.getKeyName()).isEqualTo("k1");
        assertThat(verify.getKeyVersion()).isEqualTo(1);
        assertThatThrownBy(() -> new VerifyHmacRequest("k1", "payload", "aGVsbG8", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerifyHmacRequest("k1", null, "aGVsbG8=", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DataKeyRequest：明文开关与版本校验")
    void dataKeyRequest() {
        DataKeyRequest request = new DataKeyRequest("k1", "aGVsbG8=", null, true);
        assertThat(request.getKeyName()).isEqualTo("k1");
        assertThat(request.getContext()).isEqualTo("aGVsbG8=");
        assertThat(request.getKeyVersion()).isNull();
        assertThat(request.isWithPlaintext()).isTrue();

        assertThatThrownBy(() -> new DataKeyRequest("k1", null, -1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BatchItem：input 与 reference 必填")
    void batchItem() {
        BatchItem item = new BatchItem("plain", "ref-1", null, 2);
        assertThat(item.getInput()).isEqualTo("plain");
        assertThat(item.getReference()).isEqualTo("ref-1");
        assertThat(item.getContext()).isNull();
        assertThat(item.getKeyVersion()).isEqualTo(2);
        assertThat(item.toString()).isEqualTo("BatchItem{reference='ref-1', keyVersion=2}");

        assertThatThrownBy(() -> new BatchItem(null, "ref-1", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchItem("plain", "", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BatchEncryptRequest：列表不可为空元素")
    void batchEncryptRequest() {
        BatchItem item = new BatchItem("plain", "ref-1", null, 2);
        BatchEncryptRequest request = new BatchEncryptRequest("k1", List.of(item), "aGVsbG8=", 2);
        assertThat(request.getKeyName()).isEqualTo("k1");
        assertThat(request.getItems()).containsExactly(item);
        assertThat(request.getGlobalContext()).isEqualTo("aGVsbG8=");
        assertThat(request.getGlobalKeyVersion()).isEqualTo(2);

        assertThatThrownBy(
                        () -> new BatchEncryptRequest("k1", Arrays.asList(item, null), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchEncryptRequest("k1", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BatchDecryptRequest / BatchRewrapRequest：拒绝逐项 keyVersion")
    void batchDecryptAndRewrapRejectPerItemVersion() {
        BatchItem noVersion = new BatchItem("cipher", "ref-1", null, null);
        BatchItem withVersion = new BatchItem("cipher", "ref-2", null, 3);

        BatchDecryptRequest decrypt = new BatchDecryptRequest("k1", List.of(noVersion), "");
        assertThat(decrypt.getKeyName()).isEqualTo("k1");
        assertThat(decrypt.getItems()).containsExactly(noVersion);
        assertThat(decrypt.getGlobalContext()).isNull();
        assertThatThrownBy(
                        () -> new BatchDecryptRequest("k1", List.of(noVersion, withVersion), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyVersion");

        BatchRewrapRequest rewrap = new BatchRewrapRequest("k1", List.of(noVersion), null);
        assertThat(rewrap.getKeyName()).isEqualTo("k1");
        assertThat(rewrap.getItems()).containsExactly(noVersion);
        assertThat(rewrap.getGlobalContext()).isNull();
        assertThatThrownBy(() -> new BatchRewrapRequest("k1", List.of(withVersion), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rewrap");
    }

    @Test
    @DisplayName("CreateKeyRequest：类型必填，其余保持 null 交给 provider 默认值")
    void createKeyRequest() {
        CreateKeyRequest request =
                new CreateKeyRequest("k1", KeyType.ED25519, true, false, true, false);
        assertThat(request.getKeyName()).isEqualTo("k1");
        assertThat(request.getType()).isEqualTo(KeyType.ED25519);
        assertThat(request.getDerived()).isTrue();
        assertThat(request.getConvergentEncryption()).isFalse();
        assertThat(request.getExportable()).isTrue();
        assertThat(request.getAllowPlaintextBackup()).isFalse();
        assertThat(request.toString()).isEqualTo("CreateKeyRequest{keyName='k1'}");

        assertThatThrownBy(() -> CreateKeyRequest.of("k1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreateKeyRequest.of("", KeyType.HMAC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("KeyConfigRequest：至少一项配置且版本号 >= 1")
    void keyConfigRequest() {
        KeyConfigRequest request = new KeyConfigRequest(2, 3, true);
        assertThat(request.getMinDecryptionVersion()).isEqualTo(2);
        assertThat(request.getMinEncryptionVersion()).isEqualTo(3);
        assertThat(request.getDeletionAllowed()).isTrue();

        KeyConfigRequest onlyDeletion = new KeyConfigRequest(null, null, false);
        assertThat(onlyDeletion.getDeletionAllowed()).isFalse();

        assertThatThrownBy(() -> new KeyConfigRequest(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少");
        assertThatThrownBy(() -> new KeyConfigRequest(0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyConfigRequest(null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
