package cn.byteforce.seedj.starter.im;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 系统通知配置（{@code seedj.im.system.*}）。
 *
 * @param subject 系统发送者 subject，默认 {@code system}
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "seedj.im.system")
public record ImSystemProperties(@DefaultValue("system") String subject) {

    public ImSystemProperties {
        subject = subject == null || subject.isBlank() ? "system" : subject.trim();
    }
}
