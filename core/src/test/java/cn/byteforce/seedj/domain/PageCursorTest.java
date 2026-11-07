package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** keyset 游标线格式：字段 + base64 值 + ID。 */
class PageCursorTest {

    @Test
    @DisplayName("编码/解析往返")
    void encodeAndParseRoundTrip() {
        UUID id = UUID.randomUUID();
        PageCursor cursor = new PageCursor("employee_no", "EMP-000001", id);

        PageCursor parsed = PageCursor.parse(cursor.encode());

        assertThat(parsed.sortField()).isEqualTo("employee_no");
        assertThat(parsed.sortValue()).isEqualTo("EMP-000001");
        assertThat(parsed.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("按 ID 分页的游标没有排序字段")
    void idOnlyCursor() {
        UUID id = UUID.randomUUID();
        PageCursor parsed = PageCursor.parse(new PageCursor(null, null, id).encode());

        assertThat(parsed.sortField()).isNull();
        assertThat(parsed.sortValue()).isNull();
        assertThat(parsed.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("空值编码后解析为 null")
    void blankValueBecomesNull() {
        UUID id = UUID.randomUUID();
        String encoded = "name:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[0]) + ':' + id;

        assertThat(PageCursor.parse(encoded).sortValue()).isNull();
    }

    @Test
    @DisplayName("非法游标在解析期失败")
    void invalidCursorIsRejected() {
        assertThatThrownBy(() -> PageCursor.parse(null))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThatThrownBy(() -> PageCursor.parse("not-a-cursor"))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> PageCursor.parse("name:!!!!:not-a-uuid"))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> PageCursor.parse("name:"
                + Base64.getUrlEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8))
                + ":00000000-0000-0000-0000-000000000000:extra"))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("ID 必填")
    void idIsRequired() {
        assertThatThrownBy(() -> new PageCursor("name", "v", null))
                .isInstanceOf(NullPointerException.class);
    }
}
