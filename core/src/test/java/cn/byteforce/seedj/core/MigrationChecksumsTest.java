package cn.byteforce.seedj.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationChecksumsTest {

    @Test
    @DisplayName("换行符与行尾空白不影响 checksum")
    void normalizesLineEndingsAndTrailingWhitespace() {
        String unix = "CREATE TABLE t (\n  id int\n);\n";
        String windows = "CREATE TABLE t (\r\n  id int   \r\n);\r\n\r\n";

        assertThat(MigrationChecksums.checksum(windows))
                .isEqualTo(MigrationChecksums.checksum(unix));
    }

    @Test
    @DisplayName("内部空白是内容的一部分，不做折叠")
    void keepsInternalWhitespace() {
        assertThat(MigrationChecksums.checksum("SELECT 1"))
                .isNotEqualTo(MigrationChecksums.checksum("SELECT  1"));
    }

    @Test
    @DisplayName("不同内容 checksum 不同")
    void differentContentDifferentChecksum() {
        assertThat(MigrationChecksums.checksum("CREATE TABLE a (id int)"))
                .isNotEqualTo(MigrationChecksums.checksum("CREATE TABLE b (id int)"));
    }

    @Test
    @DisplayName("checksum 覆盖回滚脚本；null 回滚保持旧值")
    void checksumCoversRollback() {
        Migration noRollback = new Migration(1, "d", "SELECT 1");
        Migration withRollback = new Migration(1, "d", "SELECT 1", "SELECT 2");
        Migration otherRollback = new Migration(1, "d", "SELECT 1", "SELECT 3");

        assertThat(MigrationChecksums.checksum(noRollback))
                .isEqualTo(MigrationChecksums.checksum("SELECT 1"));
        assertThat(MigrationChecksums.checksum(withRollback))
                .isNotEqualTo(MigrationChecksums.checksum(noRollback))
                .isNotEqualTo(MigrationChecksums.checksum(otherRollback));
    }

    @Test
    @DisplayName("迁移条目拒绝非法版本与空内容")
    void migrationValidation() {
        assertThatThrownBy(() -> new Migration(0, "v0", "SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Migration(1, "empty", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("未就绪的必需能力在 validate 阶段抛 NOT_READY")
    void capabilityValidateFailsWhenNotReady() {
        InfrastructureCapability capability = new InfrastructureCapability() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void initialize() {
            }
        };

        assertThatThrownBy(capability::validate)
                .isInstanceOf(InfrastructureException.class)
                .extracting(e -> ((InfrastructureException) e).code())
                .isEqualTo(InfrastructureErrorCode.NOT_READY.name());
        assertThat(capability.isRequired()).isTrue();
        assertThat(capability.getOrder()).isZero();
    }
}
