package cn.byteforce.seedj.pki;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * 内置 PKI 实现跑公共契约套件（每个用例一份独立状态文件）。
 */
class BcPkiProviderContractTest extends PkiProviderContractTest {

    private static final char[] PASSWORD = "test-password".toCharArray();

    @TempDir
    Path tempDir;

    private int counter;

    @Override
    protected PkiProvider newProvider() {
        Path stateFile = tempDir.resolve("pki-state-" + (++counter) + ".properties");
        return new BcPkiProvider("classpath:certs/pki-test-ca.p12", PASSWORD,
                "pki-test-ca", stateFile);
    }
}
