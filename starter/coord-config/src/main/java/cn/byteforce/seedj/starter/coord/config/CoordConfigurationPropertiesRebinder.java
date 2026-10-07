package cn.byteforce.seedj.starter.coord.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code @ConfigurationProperties} 级 rebind（R3 决议的 v1 深度）：
 * 对齐业界 rebind 语义 —— 对每个登记 Bean 执行
 * {@code destroyBean} + {@code initializeBean}，由 Boot 的绑定后处理器从
 * （已被 Watch 更新过的）环境重新绑定。
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li>仅处理 {@link CoordRefreshableBeansPostProcessor} 登记的 Bean；</li>
 *   <li>生命周期回调（{@code @PostConstruct} / {@code @PreDestroy}）会随
 *       destroy + initialize 重放 —— 属性 Bean 不应携带副作用；</li>
 *   <li>单个 Bean 失败不中断其余 Bean；失败 Bean 保留旧值并由调用方记录 / 发布。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordConfigurationPropertiesRebinder {

    private static final Logger log = LoggerFactory.getLogger(CoordConfigurationPropertiesRebinder.class);

    private final ConfigurableListableBeanFactory beanFactory;
    private final CoordRefreshableBeansPostProcessor refreshableBeans;

    /**
     * @param beanFactory      可配置 Bean 工厂
     * @param refreshableBeans 登记器（BPP）
     */
    public CoordConfigurationPropertiesRebinder(ConfigurableListableBeanFactory beanFactory,
                                                CoordRefreshableBeansPostProcessor refreshableBeans) {
        this.beanFactory = beanFactory;
        this.refreshableBeans = refreshableBeans;
    }

    /**
     * 重绑全部登记 Bean。
     *
     * @return 失败明细（bean 名 → 异常）；全部成功时为空 Map
     */
    public Map<String, Throwable> rebindAll() {
        Map<String, Throwable> failures = new LinkedHashMap<>();
        for (String beanName : refreshableBeans.refreshableBeans()) {
            try {
                rebind(beanName);
            } catch (RuntimeException e) {
                failures.put(beanName, e);
                log.error("配置重绑失败（保留旧值）: bean={}", beanName, e);
            }
        }
        return failures;
    }

    /**
     * 重绑单个 Bean。
     *
     * @param beanName bean 名
     * @return true 表示已执行重绑；false 表示非可刷新 Bean
     */
    public boolean rebind(String beanName) {
        if (!refreshableBeans.refreshableBeans().contains(beanName) || !beanFactory.containsBean(beanName)) {
            return false;
        }
        Object bean = beanFactory.getBean(beanName);
        if (bean == null) {
            return false;
        }
        Object target = resolveTarget(bean);
        if (target == null) {
            return false;
        }
        beanFactory.destroyBean(target);
        beanFactory.initializeBean(target, beanName);
        log.debug("配置重绑完成: bean={}", beanName);
        return true;
    }

    /**
     * 解析 AOP 代理的目标对象（对齐 Spring Cloud 的 rebind 口径）。
     */
    private Object resolveTarget(Object bean) {
        if (!AopUtils.isAopProxy(bean)) {
            return bean;
        }
        if (bean instanceof Advised advised) {
            try {
                return advised.getTargetSource().getTarget();
            } catch (Exception e) {
                log.warn("解析代理目标失败: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }
}
