package cn.byteforce.seedj.starter.data.mybatis;


import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CodeEnum MyBatis 配置属性。
 *
 * <p>前缀：{@code byteforce.mybatis.code-enum}</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.mybatis.code-enum")
public class CodeEnumMybatisProperties {

    /**
     * 是否启用 CodeEnum TypeHandler 自动注册。
     */
    private boolean enabled = true;

    /**
     * 扫描 CodeEnum 枚举的基础包。
     *
     * <p>为空时默认扫描 {@code @SpringBootApplication} 所在包及其子包。</p>
     */
    private Set<String> basePackages = new LinkedHashSet<>();

    /**
     * 获取是否启用。
     *
     * @return 是否启用自动注册
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用自动注册
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 获取扫描基础包。
     *
     * @return 扫描基础包集合
     */
    public Set<String> getBasePackages() { return basePackages; }

    /**
     * 设置扫描基础包。
     *
     * @param basePackages 扫描基础包集合
     */
    public void setBasePackages(Set<String> basePackages) { this.basePackages = basePackages; }
}