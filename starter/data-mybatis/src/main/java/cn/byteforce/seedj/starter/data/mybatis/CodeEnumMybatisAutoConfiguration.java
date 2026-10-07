package cn.byteforce.seedj.starter.data.mybatis;

import cn.byteforce.seedj.starter.data.mybatis.handler.CodeEnumTypeHandlerRegistrar;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CodeEnum MyBatis 自动配置。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(ConfigurationCustomizer.class)
@ConditionalOnProperty(
        prefix = "byteforce.mybatis.code-enum",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(CodeEnumMybatisProperties.class)
public class CodeEnumMybatisAutoConfiguration {

    /**
     * CodeEnum TypeHandler 注册定制器：按属性 base-packages（缺省取自动配置包）扫描并注册。
     *
     * @param properties 配置属性
     * @param ctx        应用上下文（用于解析缺省扫描包）
     * @return MyBatis 配置定制器
     */
    @Bean
    public ConfigurationCustomizer codeEnumTypeHandlerCustomizer(
            CodeEnumMybatisProperties properties,
            org.springframework.context.ApplicationContext ctx) {

        Set<String> packages = new LinkedHashSet<>(properties.getBasePackages());
        if (packages.isEmpty()) {
            packages.addAll(resolveDefaultPackages(ctx));
        }

        return new CodeEnumTypeHandlerRegistrar(packages);
    }

    private Set<String> resolveDefaultPackages(org.springframework.context.ApplicationContext ctx) {
        Set<String> packages = new LinkedHashSet<>();

        // 首选 Boot 标准扫描基包（AutoConfigurationPackages），多入口 / 公共包场景可靠
        try {
            packages.addAll(org.springframework.boot.autoconfigure.AutoConfigurationPackages.get(ctx));
        } catch (IllegalStateException ex) {
            // 非标准 Boot 上下文（如测试切片）：回退到主类扫描
        }

        if (packages.isEmpty()) {
            try {
                Class<?> mainClass = ctx.getBeansWithAnnotation(
                        org.springframework.boot.autoconfigure.SpringBootApplication.class
                ).values().stream().findFirst().map(Object::getClass).orElse(null);

                if (mainClass != null) {
                    String pkg = mainClass.getPackageName();
                    if (StringUtils.hasText(pkg)) {
                        packages.add(pkg);
                    }
                }
            } catch (Exception e) {
                // 忽略，使用兜底包
            }
        }
        if (packages.isEmpty()) {
            packages.add("cn.byteforce");
        }
        return packages;
    }
}