package cn.byteforce.seedj.iam;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IamEnabledMarkerTest {

    @Test
    void markerIsAPlainBeanType() {
        assertThat(new IamEnabledMarker()).isNotNull();
    }
}
