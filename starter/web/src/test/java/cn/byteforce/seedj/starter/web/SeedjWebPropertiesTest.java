package cn.byteforce.seedj.starter.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeedjWebProperties} 默认值与访问器契约测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class SeedjWebPropertiesTest {

    @Test
    void defaultsAreStable() {
        SeedjWebProperties properties = new SeedjWebProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isErrorHandlingEnabled()).isTrue();
        assertThat(properties.isRequestContextEnabled()).isTrue();
        assertThat(properties.getTraceIdHeader()).isEqualTo("X-Trace-Id");
        assertThat(properties.isExposeTraceId()).isTrue();
    }

    @Test
    void settersOverrideDefaults() {
        SeedjWebProperties properties = new SeedjWebProperties();
        properties.setEnabled(false);
        properties.setErrorHandlingEnabled(false);
        properties.setRequestContextEnabled(false);
        properties.setTraceIdHeader("X-Request-Id");
        properties.setExposeTraceId(false);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isErrorHandlingEnabled()).isFalse();
        assertThat(properties.isRequestContextEnabled()).isFalse();
        assertThat(properties.getTraceIdHeader()).isEqualTo("X-Request-Id");
        assertThat(properties.isExposeTraceId()).isFalse();
    }
}
