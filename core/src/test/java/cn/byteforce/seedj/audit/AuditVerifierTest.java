package cn.byteforce.seedj.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计链校验入口：空链与违规项形状。
 */
class AuditVerifierTest {

    @Test
    @DisplayName("null 与空链都返回 empty_chain 违规")
    void emptyChainIsReported() {
        assertThat(AuditVerifier.verify(null)).singleElement().satisfies(violation -> {
            assertThat(violation.getType()).isEqualTo("empty_chain");
            assertThat(violation.getSequence()).isZero();
            assertThat(violation.getMessage()).isEqualTo("审计链为空");
            assertThat(violation.getExpected()).isNull();
            assertThat(violation.getActual()).isNull();
        });

        assertThat(AuditVerifier.verify(List.of(), null)).singleElement()
                .extracting(AuditVerifier.AuditViolation::getType)
                .isEqualTo("empty_chain");
    }
}
