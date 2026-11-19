package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.im.ImMessageKind;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        if (kind == ImMessageKind.CARD) {
            validateCard(body);
            return;
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

    private static void validateCard(Map<String, Object> body) {
        if (body == null) {
            throw invalidCard("CARD 消息体不能为空");
        }
        for (String field : List.of("schema", "title", "fallback_text")) {
            Object value = body.get(field);
            if (value == null || String.valueOf(value).isBlank()) {
                throw invalidCard("CARD 消息缺少 " + field);
            }
        }
        Object actions = body.get("actions");
        if (!(actions instanceof List<?> list) || list.isEmpty()) {
            throw invalidCard("CARD 消息必须带非空 actions");
        }
        Set<String> ids = new HashSet<>();
        for (Object action : list) {
            if (!(action instanceof Map<?, ?> map)) {
                throw invalidCard("CARD action 必须是对象");
            }
            Object id = map.get("id");
            Object label = map.get("label");
            if (id == null || String.valueOf(id).isBlank()) {
                throw invalidCard("CARD action 缺少 id");
            }
            if (label == null || String.valueOf(label).isBlank()) {
                throw invalidCard("CARD action 缺少 label");
            }
            if (!ids.add(String.valueOf(id))) {
                throw invalidCard("CARD action id 重复: " + id);
            }
        }
    }

    private static ContractException invalidCard(String message) {
        return new ContractException(ContractErrorCode.INVALID_PAYLOAD, message);
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
