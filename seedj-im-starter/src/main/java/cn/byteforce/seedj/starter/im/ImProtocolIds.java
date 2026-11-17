package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.util.UUID;

/** 协议字段解析：会话/消息标识与设备标识的唯一口径。 */
final class ImProtocolIds {

    private ImProtocolIds() {}

    static UUID conversation(String value) {
        return uuid(value, "会话标识");
    }

    static UUID message(String value) {
        return uuid(value, "消息标识");
    }

    static String device(String deviceId) {
        return deviceId == null || deviceId.isBlank() ? "unknown" : deviceId;
    }

    private static UUID uuid(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, label + "不能为空");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, label + "格式非法: " + value);
        }
    }
}
