package cn.byteforce.seedj.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 会话去重键与成员任期的纯函数语义。 */
class ImConversationTest {

    @Test
    @DisplayName("单聊去重键与参数顺序无关")
    void directKeyIsOrderIndependent() {
        assertThat(ImConversation.directKey("b", "a")).isEqualTo("a|b");
        assertThat(ImConversation.directKey("a", "b")).isEqualTo("a|b");
    }

    @Test
    @DisplayName("空白 subject 不能构成去重键")
    void directKeyRejectsBlankSubject() {
        assertThatThrownBy(() -> ImConversation.directKey("a", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("单聊必须有去重键，群聊不需要")
    void singleConversationRequiresDirectKey() {
        assertThatThrownBy(
                        () ->
                                new ImConversation(
                                        UUID.randomUUID(),
                                        "t1",
                                        ImConversationKind.SINGLE,
                                        null,
                                        "u-1",
                                        null,
                                        Instant.now(),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);

        ImConversation group =
                new ImConversation(
                        UUID.randomUUID(),
                        "t1",
                        ImConversationKind.GROUP,
                        "研发",
                        "u-1",
                        null,
                        Instant.now(),
                        null);
        assertThat(group.directKey()).isNull();
        assertThat(group.updatedAt()).isEqualTo(group.createdAt());
    }

    @Test
    @DisplayName("空白标题归一为 null，群聊允许空去重键")
    void blankTitleBecomesNull() {
        ImConversation group =
                new ImConversation(
                        UUID.randomUUID(),
                        "t1",
                        ImConversationKind.GROUP,
                        "  ",
                        "u-1",
                        " ",
                        Instant.now(),
                        Instant.now().plusSeconds(5));

        assertThat(group.title()).isNull();
        assertThat(group.directKey()).isNull();
        assertThat(group.updatedAt()).isAfter(group.createdAt());
    }

    @Test
    @DisplayName("必填项缺失立即失败")
    void requiredComponentsAreChecked() {
        Instant now = Instant.now();
        assertThatThrownBy(
                        () ->
                                new ImConversation(
                                        null,
                                        "t1",
                                        ImConversationKind.GROUP,
                                        null,
                                        "u-1",
                                        null,
                                        now,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new ImConversation(
                                        UUID.randomUUID(),
                                        "t1",
                                        null,
                                        null,
                                        "u-1",
                                        null,
                                        now,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new ImConversation(
                                        UUID.randomUUID(),
                                        "t1",
                                        ImConversationKind.GROUP,
                                        null,
                                        null,
                                        null,
                                        now,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new ImConversation(
                                        UUID.randomUUID(),
                                        "t1",
                                        ImConversationKind.GROUP,
                                        null,
                                        "u-1",
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("空白租户回落到默认租户")
    void blankTenantFallsBackToDefault() {
        ImConversation conversation =
                new ImConversation(
                        UUID.randomUUID(),
                        "  ",
                        ImConversationKind.SINGLE,
                        null,
                        "u-1",
                        ImConversation.directKey("u-1", "u-2"),
                        Instant.now(),
                        null);
        assertThat(conversation.tenantId()).isEqualTo("default");
    }

    @Test
    @DisplayName("成员任期的时点判定")
    void memberActiveAt() {
        ImMember tenure =
                new ImMember(
                        UUID.randomUUID(),
                        "u-1",
                        10,
                        20L,
                        ImMemberRole.MEMBER,
                        null,
                        Instant.now());

        assertThat(tenure.active()).isFalse();
        assertThat(tenure.activeAt(9)).isFalse();
        assertThat(tenure.activeAt(10)).isTrue();
        assertThat(tenure.activeAt(19)).isTrue();
        assertThat(tenure.activeAt(20)).isFalse();
    }

    @Test
    @DisplayName("空白 subject 与非法序号立即失败")
    void memberValidations() {
        Instant now = Instant.now();

        assertThatThrownBy(
                        () ->
                                new ImMember(
                                        UUID.randomUUID(),
                                        " ",
                                        1,
                                        null,
                                        ImMemberRole.MEMBER,
                                        null,
                                        now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ImMember(
                                        UUID.randomUUID(),
                                        "u-1",
                                        0,
                                        null,
                                        ImMemberRole.MEMBER,
                                        null,
                                        now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImMember(null, "u-1", 1, null, ImMemberRole.MEMBER, null, now))
                .isInstanceOf(NullPointerException.class);

        ImMember active =
                new ImMember(UUID.randomUUID(), "u-1", 1, null, ImMemberRole.OWNER, null, now);
        assertThat(active.active()).isTrue();
        assertThat(active.activeAt(1)).isTrue();
        assertThat(active.activeAt(1_000)).isTrue();
    }

    @Test
    @DisplayName("退群序号必须晚于入群序号")
    void leftSeqMustFollowJoinedSeq() {
        assertThatThrownBy(
                        () ->
                                new ImMember(
                                        UUID.randomUUID(),
                                        "u-1",
                                        10,
                                        10L,
                                        ImMemberRole.MEMBER,
                                        null,
                                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
