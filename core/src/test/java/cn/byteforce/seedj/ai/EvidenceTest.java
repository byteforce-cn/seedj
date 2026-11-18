package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 证据的构造口径：类别必填且不得空白，引用与摘要可缺省。
 *
 * <p>类别是复核的入口：没有类别的证据无法判断该去哪个来源系统核对。
 */
class EvidenceTest {

    @Test
    @DisplayName("证据类别缺失时拒绝")
    void kind_is_mandatory() {
        assertThatThrownBy(() -> new Evidence(null, "im://m-1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("kind");
    }

    @Test
    @DisplayName("证据类别为空白时拒绝")
    void blank_kind_is_rejected() {
        assertThatThrownBy(() -> new Evidence(" ", "im://m-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
    }

    @Test
    @DisplayName("引用与摘要可缺省")
    void ref_and_digest_may_be_absent() {
        Evidence evidence = new Evidence("policy", null, null);

        assertThat(evidence.ref()).isNull();
        assertThat(evidence.digest()).isNull();
    }
}
