package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.im.ImMessageKind;
import java.math.BigDecimal;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 消息体校验：正文上限与附件引用。
 *
 * <p>附件只是引用：{@code IMAGE} / {@code FILE} 必须带非空 {@code key}（对象存储 key）， {@code size}
 * 可省略，但给了就必须是十进制数字且不超过配置上限。二进制上传、下载与鉴权都归宿主， starter 不代理文件，也不把二进制放进消息体或事件总线。
 */
final class ImAttachments {

    private ImAttachments() {}

    static void validate(
            ImMessageKind kind,
            Map<String, Object> body,
            int maxBodyBytes,
            int maxAttachmentBytes,
            JsonMapper json) {
        int size = json.writeValueAsBytes(body == null ? Map.of() : body).length;
        if (size > maxBodyBytes) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "消息体超过上限: " + size + " > " + maxBodyBytes);
        }
        if (kind != ImMessageKind.IMAGE && kind != ImMessageKind.FILE) {
            return;
        }
        Object key = body == null ? null : body.get("key");
        if (key == null || String.valueOf(key).isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "附件消息必须带非空的 key");
        }
        Object declaredSize = body.get("size");
        if (declaredSize == null) {
            return;
        }
        long bytes = bytes(declaredSize);
        if (bytes < 0 || bytes > maxAttachmentBytes) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD,
                    "附件超过上限: " + bytes + " > " + maxAttachmentBytes);
        }
    }

    private static long bytes(Object declaredSize) {
        if (declaredSize instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).longValueExact();
            } catch (NumberFormatException | ArithmeticException notAnInteger) {
                throw invalid(declaredSize);
            }
        }
        if (declaredSize instanceof String text && text.strip().matches("\\d+")) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException overflow) {
                throw invalid(text);
            }
        }
        throw invalid(declaredSize);
    }

    private static ContractException invalid(Object declaredSize) {
        return new ContractException(
                ContractErrorCode.INVALID_PAYLOAD, "附件 size 非法: " + declaredSize);
    }
}
