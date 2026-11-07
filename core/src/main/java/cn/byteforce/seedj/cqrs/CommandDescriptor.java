package cn.byteforce.seedj.cqrs;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 命令的唯一元数据：类型、对象类型、载荷类型、权限、校验器、脱敏器。
 *
 * <p>命令目录、HTTP 边界与业务代码都从这里读取同一份声明，避免权限/校验/脱敏分别散落在 controller、agent 与装配代码里。线格式字段（{@code type}/{@code
 * objectType}）保持稳定。
 *
 * @param type 线格式命令类型，匹配 {@code ^[a-z][a-z0-9_.]*$}
 * @param objectType 对象类型（对象表注册名）
 * @param payloadType 载荷类型
 * @param permission 执行所需权限；为空表示必须在目录装配阶段补齐
 * @param validator 载荷校验器；默认不做业务校验
 * @param redactor 命令记录载荷脱敏器；默认原样保存
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandDescriptor(
        String type,
        String objectType,
        Class<?> payloadType,
        String permission,
        CommandValidator validator,
        CommandPayloadRedactor redactor) {

    private static final Pattern TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_.]*$");

    public CommandDescriptor {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("command type is required");
        }
        if (!TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException(
                    "command type must match " + TYPE_PATTERN.pattern() + ": " + type);
        }
        if (objectType == null || objectType.isBlank()) {
            throw new IllegalArgumentException("command objectType is required");
        }
        payloadType = Objects.requireNonNull(payloadType, "payloadType");
        permission = permission == null || permission.isBlank() ? null : permission;
        validator = validator == null ? CommandValidator.none() : validator;
        redactor = redactor == null ? CommandPayloadRedactor.noop() : redactor;
    }

    /**
     * 声明式构造器。
     *
     * @return 新构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 命令描述构造器；缺字段在 {@link #build()} 失败，而不是留到运行时。 */
    public static final class Builder {

        private String type;

        private String objectType;

        private Class<?> payloadType;

        private String permission;

        private CommandValidator validator = CommandValidator.none();

        private CommandPayloadRedactor redactor = CommandPayloadRedactor.noop();

        private Builder() {}

        public Builder type(String value) {
            this.type = value;
            return this;
        }

        public Builder objectType(String value) {
            this.objectType = value;
            return this;
        }

        public Builder payloadType(Class<?> value) {
            this.payloadType = value;
            return this;
        }

        public Builder permission(String value) {
            this.permission = value;
            return this;
        }

        public Builder validator(CommandValidator value) {
            this.validator = value;
            return this;
        }

        public Builder redactor(CommandPayloadRedactor value) {
            this.redactor = value;
            return this;
        }

        /**
         * 构造描述。
         *
         * @return 不可变描述
         */
        public CommandDescriptor build() {
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("command descriptor requires type");
            }
            if (objectType == null || objectType.isBlank()) {
                throw new IllegalStateException("command descriptor requires objectType");
            }
            if (payloadType == null) {
                throw new IllegalStateException("command descriptor requires payloadType");
            }
            return new CommandDescriptor(
                    type, objectType, payloadType, permission, validator, redactor);
        }
    }
}
