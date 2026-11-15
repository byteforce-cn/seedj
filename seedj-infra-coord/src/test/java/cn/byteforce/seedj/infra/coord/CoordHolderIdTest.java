package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordHolderIdTest {

    @Test
    @DisplayName("显式配置优先")
    void configuredHolderIdWins() {
        CoordProperties properties = new CoordProperties();
        properties.getLock().setHolderId("node-a:42");

        assertThat(CoordHolderId.of(properties)).isEqualTo("node-a:42");
    }

    @Test
    @DisplayName("默认值包含主机名、进程号与每次启动变化的随机后缀")
    void derivesFromHostAndProcess() {
        String first = CoordHolderId.derive("node-1");
        String second = CoordHolderId.derive("node-1");

        assertThat(first).startsWith("node-1:" + ProcessHandle.current().pid() + ":");
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("主机名缺失时退化为 local")
    void fallsBackWhenHostIsUnknown() {
        assertThat(CoordHolderId.derive(null)).startsWith("local:");
        assertThat(CoordHolderId.derive("  ")).startsWith("local:");
    }

    @Test
    @DisplayName("未配置时生成默认值")
    void unconfiguredPropertiesUseDefault() {
        assertThat(CoordHolderId.of(new CoordProperties())).isNotBlank();
    }
}
