package cn.byteforce.seedj.starter.data.mybatis.handler;

import cn.byteforce.seedj.core.CodeEnum;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.LinkedHashSet;
import java.util.Set;


/**
 * 在 MyBatis 初始化时把扫描到的 CodeEnum 枚举注册为 {@link CodeEnumTypeHandler}
 * （经 MyBatis-Plus 的 {@link ConfigurationCustomizer} 扩展点）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CodeEnumTypeHandlerRegistrar implements ConfigurationCustomizer {

    private static final Logger log = LoggerFactory.getLogger(CodeEnumTypeHandlerRegistrar.class);

    private final Set<String> basePackages;

    /**
     * 构造注册器。
     *
     * @param basePackages 扫描基础包（非空；缺省包由调用方解析后传入）
     */
    public CodeEnumTypeHandlerRegistrar(Set<String> basePackages) {
        this.basePackages = basePackages;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(TypeHandlerRegistry registry, Class<?> enumClass) {
        registry.register((Class<? extends Enum>) enumClass,
                new CodeEnumTypeHandler(enumClass));
    }

    /**
     * 扫描所有实现 {@link CodeEnum} 的枚举类。
     */
    private Set<Class<?>> scanCodeEnumClasses() {
        Set<Class<?>> result = new LinkedHashSet<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(CodeEnum.class));

        for (String basePackage : basePackages) {
            for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    if (clazz.isEnum()) {
                        result.add(clazz);
                    }
                } catch (ClassNotFoundException e) {
                    log.warn("[CodeEnum] 无法加载类: {}", bd.getBeanClassName(), e);
                }
            }
        }
        return result;
    }

    @Override
    public void customize(MybatisConfiguration configuration) {
        Set<Class<?>> enumClasses = scanCodeEnumClasses();
        TypeHandlerRegistry registry = configuration.getTypeHandlerRegistry();

        for (Class<?> clazz : enumClasses) {
            register(registry, clazz);
        }
        if (enumClasses.isEmpty()) {
            log.warn("[CodeEnum] 未扫描到任何 CodeEnum 枚举（扫描包: {}）；"
                            + "若枚举定义在其他包，请配置 byteforce.mybatis.code-enum.base-packages",
                    basePackages);
        } else {
            log.info("[CodeEnum] 已注册 {} 个 CodeEnum TypeHandler: {}",
                    enumClasses.size(), enumClasses.stream().map(Class::getSimpleName).toList());
        }
    }
}