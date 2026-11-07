package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * keyset 分页游标：排序字段值 + 对象 ID。
 *
 * <p>线格式 {@code sortField:base64(value):id}；值在存储层按字段类型转换。游标只 能配合声明的排序字段使用，排序字段为空时退化为按 ID 分页。
 *
 * @param sortField 排序字段；{@code null} 表示按 ID
 * @param sortValue 排序字段值；按 ID 时为 {@code null}
 * @param id 上一页最后一条的对象 ID
 * @author ByteForce
 * @since 1.0.0
 */
public record PageCursor(String sortField, Object sortValue, UUID id) {

    public PageCursor {
        Objects.requireNonNull(id, "id");
    }

    /**
     * 编码为线格式。
     *
     * @return 游标字符串
     */
    public String encode() {
        String value = sortValue == null ? "" : String.valueOf(sortValue);
        return (sortField == null ? "" : sortField)
                + ':'
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(value.getBytes(StandardCharsets.UTF_8))
                + ':'
                + id;
    }

    /**
     * 解析线格式。
     *
     * @param cursor 游标字符串
     * @return 游标
     */
    public static PageCursor parse(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "cursor is required");
        }
        String[] parts = cursor.split(":", 3);
        if (parts.length != 3) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "invalid cursor");
        }
        try {
            String field = parts[0].isBlank() ? null : parts[0];
            String value =
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            UUID id = UUID.fromString(parts[2]);
            return new PageCursor(field, value.isBlank() ? null : value, id);
        } catch (IllegalArgumentException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "invalid cursor");
        }
    }
}
