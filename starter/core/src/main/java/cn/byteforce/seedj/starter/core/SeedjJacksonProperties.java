package cn.byteforce.seedj.starter.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * seedj Jackson 定制配置属性。
 *
 * <p>前缀：{@code byteforce.jackson}</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.jackson")
public class SeedjJacksonProperties {

    /**
     * 是否启用 seedj Jackson 定制（CodeEnum code 契约、Long 字符串化等），默认 {@code true}。
     */
    private boolean enabled = true;

    /**
     * 是否将 {@code Long} 序列化为字符串（防止前端 JS 精度丢失），默认 {@code true}；
     * 反序列化同时接受数字与字符串。
     *
     * <p>注意：该开关作用于<b>全局长整型</b>字段，属于对外 wire 契约的一部分
     * （见 seedj ADR-0003）；如仅部分字段需要字符串化，请关闭本开关并使用注解单独控制。</p>
     */
    private boolean longAsString = true;

    /**
     * 返回是否启用 seedj Jackson 定制。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用 seedj Jackson 定制。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回是否将 Long 序列化为字符串。
     *
     * @return 是否字符串化
     */
    public boolean isLongAsString() {
        return longAsString;
    }

    /**
     * 设置是否将 Long 序列化为字符串。
     *
     * @param longAsString 是否字符串化
     */
    public void setLongAsString(boolean longAsString) {
        this.longAsString = longAsString;
    }
}
