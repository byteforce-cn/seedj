package cn.byteforce.seedj.starter.coord.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 收集 {@code @ConfigurationProperties} Bean 名（含 {@code @Bean} 工厂方法上的注解），
 * 供 {@link CoordConfigurationPropertiesRebinder} 做刷新级 rebind。
 *
 * <p>Boot 4 不提供 {@code ConfigurationPropertiesBeans}（Spring Cloud 内部设施），
 * 本类等价自研：在初始化后检查 bean 定义（类 / 工厂方法）上的
 * {@code @ConfigurationProperties} 注解。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordRefreshableBeansPostProcessor implements BeanPostProcessor, BeanFactoryAware {

    private final Set<String> refreshableBeans = ConcurrentHashMap.newKeySet();

    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof ConfigurableListableBeanFactory configurableBeanFactory) {
            this.beanFactory = configurableBeanFactory;
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 无 BeanDefinition 的 Bean（如测试框架手工 initializeBean 的实例 / registerSingleton）
        // 没有可解析的注解元数据，直接跳过（findAnnotationOnBean 会抛 NoSuchBeanDefinitionException）
        if (beanFactory != null
                && beanFactory.containsBeanDefinition(beanName)
                && beanFactory.findAnnotationOnBean(beanName, ConfigurationProperties.class, false) != null) {
            refreshableBeans.add(beanName);
        }
        return bean;
    }

    /**
     * 已登记的 {@code @ConfigurationProperties} Bean 名（不可变快照）。
     *
     * @return bean 名集合
     */
    public Set<String> refreshableBeans() {
        return Set.copyOf(refreshableBeans);
    }
}
