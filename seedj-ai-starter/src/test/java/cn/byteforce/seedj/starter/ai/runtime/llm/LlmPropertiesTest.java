package cn.byteforce.seedj.starter.ai.runtime.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** LLM 配置的启动期校验与脱敏。 */
class LlmPropertiesTest {

    @Test
    @DisplayName("启用但缺 base-url：点名配置键")
    void missing_base_url_names_the_key() {
        assertThatThrownBy(
                        () ->
                                new LlmProperties(
                                        true,
                                        " ",
                                        "sk-secret",
                                        "gpt",
                                        null,
                                        null,
                                        Duration.ofSeconds(5),
                                        2,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seedj.ai.llm.base-url");
    }

    @Test
    @DisplayName("启用但缺 model：点名配置键")
    void missing_model_names_the_key() {
        assertThatThrownBy(
                        () ->
                                new LlmProperties(
                                        true,
                                        "http://localhost:8000/v1",
                                        null,
                                        null,
                                        null,
                                        null,
                                        Duration.ofSeconds(5),
                                        2,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seedj.ai.llm.model");
    }

    @Test
    @DisplayName("关闭时允许空配置：只声明 enabled=false 就能启动")
    void disabled_allows_empty_configuration() {
        LlmProperties properties =
                new LlmProperties(false, null, null, null, null, null, null, 2, null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.timeout()).isEqualTo(LlmProperties.DEFAULT_TIMEOUT);
        assertThat(properties.toolChoice()).isEqualTo(LlmProperties.ToolChoice.REQUIRED);
    }

    @Test
    @DisplayName("重试次数小于 1 或超时非正：拒绝")
    void rejects_invalid_retry_and_timeout() {
        assertThatThrownBy(
                        () ->
                                new LlmProperties(
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Duration.ofSeconds(1),
                                        0,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts");
        assertThatThrownBy(
                        () ->
                                new LlmProperties(
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Duration.ZERO,
                                        1,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("toString 不泄露 api-key")
    void toString_redacts_api_key() {
        LlmProperties properties =
                new LlmProperties(
                        true,
                        "http://localhost:8000/v1",
                        "sk-super-secret",
                        "gpt",
                        0.2,
                        512,
                        Duration.ofSeconds(5),
                        2,
                        null);

        assertThat(properties.toString()).contains("apiKey=***").doesNotContain("sk-super-secret");
    }
}
