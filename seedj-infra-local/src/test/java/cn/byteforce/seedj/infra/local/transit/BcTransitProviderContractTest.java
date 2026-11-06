package cn.byteforce.seedj.infra.local.transit;

import cn.byteforce.seedj.transit.Ciphertext;
import cn.byteforce.seedj.transit.CreateKeyRequest;
import cn.byteforce.seedj.transit.DecryptRequest;
import cn.byteforce.seedj.transit.EncryptRequest;
import cn.byteforce.seedj.transit.ExportedKey;
import cn.byteforce.seedj.transit.KeyConfigRequest;
import cn.byteforce.seedj.transit.KeyType;
import cn.byteforce.seedj.transit.TransitErrorCode;
import cn.byteforce.seedj.transit.TransitException;
import cn.byteforce.seedj.transit.TransitProvider;
import cn.byteforce.seedj.transit.TransitProviderContractTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置 Transit 实现：契约套件 + 磁盘共享状态。
 */
class BcTransitProviderContractTest extends TransitProviderContractTest {

    @Override
    protected TransitProvider newProvider() {
        return new BcTransitProvider();
    }

    @Test
    @DisplayName("同一状态文件的多实例共享密钥与版本历史")
    void stateIsSharedAcrossInstances(@TempDir Path dir) {
        Path stateFile = dir.resolve("transit.properties");

        BcTransitProvider first = BcTransitProvider.fileBacked(stateFile);
        first.keyAdmin().createKey(CreateKeyRequest.of("shared", KeyType.AES256_GCM96));
        Ciphertext ciphertext = first.crypto().encrypt(EncryptRequest.of("shared", "secret"));
        first.keyAdmin().rotateKey("shared");

        BcTransitProvider second = BcTransitProvider.fileBacked(stateFile);
        assertThat(second.keyAdmin().readKey("shared").getLatestVersion()).isEqualTo(2);
        assertThat(second.crypto().decrypt(DecryptRequest.of("shared", ciphertext.getValue())))
                .isEqualTo("secret");
        assertThat(second.crypto().encrypt(EncryptRequest.of("shared", "x")).getKeyVersion())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("状态文件里不出现明文密钥以外的敏感字段，且重启后仍可解密")
    void stateSurvivesRestart(@TempDir Path dir) throws Exception {
        Path stateFile = dir.resolve("transit.properties");

        BcTransitProvider first = BcTransitProvider.fileBacked(stateFile);
        first.keyAdmin().createKey(CreateKeyRequest.of("k", KeyType.AES256_GCM96));
        Ciphertext ciphertext = first.crypto().encrypt(EncryptRequest.of("k", "persisted"));
        first.close();

        assertThat(Files.readString(stateFile)).contains("key.").doesNotContain("persisted");

        BcTransitProvider reopened = BcTransitProvider.fileBacked(stateFile);
        assertThat(reopened.crypto().decrypt(DecryptRequest.of("k", ciphertext.getValue())))
                .isEqualTo("persisted");
    }

    @Test
    @DisplayName("导出：非 exportable key 抛 NOT_EXPORTABLE，exportable key 可导出密钥材料")
    void exportKey() {
        BcTransitProvider provider = new BcTransitProvider();
        provider.keyAdmin().createKey(new CreateKeyRequest("plain", KeyType.AES256_GCM96,
                null, null, false, null));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> provider.keyAdmin().exportKey("plain", KeyType.AES256_GCM96, null))
                .isInstanceOf(TransitException.class)
                .extracting(e -> ((TransitException) e).getErrorCode())
                .isEqualTo(TransitErrorCode.NOT_EXPORTABLE);

        provider.keyAdmin().createKey(new CreateKeyRequest("open", KeyType.AES256_GCM96,
                null, null, true, null));
        ExportedKey exported = provider.keyAdmin().exportKey("open", KeyType.AES256_GCM96, 1);

        assertThat(exported.getKeys()).containsKey(1);
        assertThat(exported.isContainsPrivateKey()).isFalse();
    }

    @Test
    @DisplayName("configureKey 调整最小版本后，旧版本无法解密")
    void configureMinVersions() {
        BcTransitProvider provider = new BcTransitProvider();
        provider.keyAdmin().createKey(CreateKeyRequest.of("k", KeyType.AES256_GCM96));
        Ciphertext old = provider.crypto().encrypt(EncryptRequest.of("k", "v1"));
        provider.keyAdmin().rotateKey("k");

        provider.keyAdmin().configureKey("k", new KeyConfigRequest(2, null, null));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> provider.crypto().decrypt(DecryptRequest.of("k", old.getValue())))
                .isInstanceOf(TransitException.class)
                .extracting(e -> ((TransitException) e).getErrorCode())
                .isEqualTo(TransitErrorCode.VERSION_NOT_FOUND);
    }
}
