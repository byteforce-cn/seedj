package cn.byteforce.seedj.infra.local.pki;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seedj.pki")
public class PkiProperties {

    /** 是否启用内置 PKI 自动配置 */
    private boolean enabled = true;

    /** CA keystore：classpath:certs/pki-test-ca.p12 / file:/abs/path.p12 / /abs/path.p12 */
    private String caResource = "classpath:certs/pki-test-ca.p12";

    private String caPassword = "test-password";

    private String caAlias = "pki-test-ca";

    /** 状态文件（签发 + 吊销记录）。多服务应指向同一路径 */
    private Path revocationStore =
            Path.of(System.getProperty("java.io.tmpdir"), "seedj-pki-state.properties");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCaResource() {
        return caResource;
    }

    public void setCaResource(String caResource) {
        this.caResource = caResource;
    }

    public String getCaPassword() {
        return caPassword;
    }

    public void setCaPassword(String caPassword) {
        this.caPassword = caPassword;
    }

    public String getCaAlias() {
        return caAlias;
    }

    public void setCaAlias(String caAlias) {
        this.caAlias = caAlias;
    }

    public Path getRevocationStore() {
        return revocationStore;
    }

    public void setRevocationStore(Path revocationStore) {
        this.revocationStore = revocationStore;
    }
}
