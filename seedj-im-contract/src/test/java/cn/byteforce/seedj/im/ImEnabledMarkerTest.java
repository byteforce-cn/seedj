package cn.byteforce.seedj.im;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImEnabledMarkerTest {

    @Test
    void markerIsAPlainBeanType() {
        assertThat(new ImEnabledMarker()).isNotNull();
    }
}
