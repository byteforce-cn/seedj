package cn.byteforce.seedj.workflow.spi;

import java.util.Map;
import java.util.Objects;

/** 参与者声明。 */
public record ParticipantSpec(
        ParticipantType type, String value, Map<String, String> attributes
) {
    public ParticipantSpec {
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
