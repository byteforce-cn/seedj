package cn.byteforce.seedj.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 消息值类型：body 归一化、客户端幂等键与草稿校验。 */
class ImMessageTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @Test
    @DisplayName("空 body 归一为空 Map，非空 body 只读拷贝")
    void bodyIsNormalised() {
        assertThat(message(null).body()).isEmpty();
        assertThat(message(Map.of()).body()).isEmpty();

        Map<String, Object> source = new HashMap<>();
        source.put("text", "hi");
        ImMessage message = message(source);
        source.put("text", "changed");

        assertThat(message.body()).containsEntry("text", "hi");
        assertThatThrownBy(() -> message.body().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("空白客户端幂等键归一为 null")
    void blankClientMsgIdBecomesNull() {
        assertThat(message(Map.of()).clientMsgId()).isNull();
        assertThat(
                        new ImMessage(
                                        UUID.randomUUID(),
                                        "default",
                                        UUID.randomUUID(),
                                        1,
                                        "u-1",
                                        ImMessageKind.TEXT,
                                        Map.of(),
                                        "  ",
                                        NOW,
                                        null,
                                        null)
                                .clientMsgId())
                .isNull();
    }

    @Test
    @DisplayName("必填项缺失立即失败")
    void requiredComponentsAreChecked() {
        assertThatThrownBy(
                        () ->
                                new ImMessage(
                                        null,
                                        "default",
                                        UUID.randomUUID(),
                                        1,
                                        "u-1",
                                        ImMessageKind.TEXT,
                                        Map.of(),
                                        null,
                                        NOW,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new ImMessage(
                                        UUID.randomUUID(),
                                        "default",
                                        null,
                                        1,
                                        "u-1",
                                        ImMessageKind.TEXT,
                                        Map.of(),
                                        null,
                                        NOW,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("草稿要求正序号，body 与幂等键按同一规则归一")
    void draftNormalisation() {
        ImMessageDraft draft =
                new ImMessageDraft(
                        UUID.randomUUID(),
                        "default",
                        UUID.randomUUID(),
                        5,
                        "u-1",
                        ImMessageKind.IMAGE,
                        null,
                        " ");

        assertThat(draft.body()).isEmpty();
        assertThat(draft.clientMsgId()).isNull();

        assertThatThrownBy(
                        () ->
                                new ImMessageDraft(
                                        UUID.randomUUID(),
                                        "default",
                                        UUID.randomUUID(),
                                        0,
                                        "u-1",
                                        ImMessageKind.TEXT,
                                        Map.of(),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ImMessage message(Map<String, Object> body) {
        return new ImMessage(
                UUID.randomUUID(),
                "default",
                UUID.randomUUID(),
                1,
                "u-1",
                ImMessageKind.TEXT,
                body,
                null,
                NOW,
                null,
                null);
    }
}
