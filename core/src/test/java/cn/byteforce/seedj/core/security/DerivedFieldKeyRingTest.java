package cn.byteforce.seedj.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 派生字段密钥：生产缺失即失败，测试密钥只能显式构造。 */
class DerivedFieldKeyRingTest {

    @Test
    @DisplayName("缺少 SEEDJ_FIELD_HASH_KEYS：fromEnvironment 启动期失败，不回退测试密钥")
    void fromEnvironmentFailsWithoutKeys() {
        Assumptions.assumeTrue(System.getenv(DerivedFieldKeyRing.ENVIRONMENT_KEY) == null,
                "环境已注入密钥，跳过缺失场景");

        assertThatThrownBy(DerivedFieldKeyRing::fromEnvironment)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DerivedFieldKeyRing.ENVIRONMENT_KEY);
    }

    @Test
    @DisplayName("forTests 可显式构造密钥环；首密钥为主")
    void forTestsBuildsExplicitRing() {
        DerivedFieldKeyRing ring = DerivedFieldKeyRing.forTests("k1", "k2");

        assertThat(ring.primary()).isEqualTo("k1");
        assertThat(ring.all()).containsExactly("k1", "k2");
    }

    @Test
    @DisplayName("空密钥列表失败")
    void emptyKeysFail() {
        assertThatThrownBy(DerivedFieldKeyRing::new)
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DerivedFieldKeyRing.forTests(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
