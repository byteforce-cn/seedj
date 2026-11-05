package cn.byteforce.seedj.it;

import cn.byteforce.seedj.biz.security.BizSecurityAutoConfiguration;
import cn.byteforce.seedj.iam.config.IamServerAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链路顺序门禁（ADR-0007）。
 *
 * <p>Spring Security 按 {@code @Order} 依次匹配，第一条匹配的链生效。历史上
 * IAM 的 {@code defaultSecurityFilterChain} 是无 matcher 的 catch-all 且
 * {@code @Order(5)}，在同时装配 biz 与 iam 的宿主里会把业务链整条吃掉
 * （{@code /api/**} 被重定向到 {@code /login}）。</p>
 *
 * <p>规则：只有最后一条链可以不写 matcher，且它必须是
 * {@link Ordered#LOWEST_PRECEDENCE}。</p>
 */
class BizIamChainOrderTest {

    @Test
    @DisplayName("biz 业务链在 IAM 浏览器登录链之前")
    void bizChainPrecedesIamBrowserChain() {
        int biz = chainOrder(BizSecurityAutoConfiguration.WebSecurityConfiguration.class,
                "bizSecurityFilterChain");
        int iam = chainOrder(IamServerAutoConfiguration.class, "defaultSecurityFilterChain");

        assertThat(biz).isEqualTo(BizSecurityAutoConfiguration.BIZ_FILTER_CHAIN_ORDER);
        assertThat(iam).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(biz).isLessThan(iam);
    }

    private static int chainOrder(Class<?> type, String beanMethodName) {
        Method method = Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(beanMethodName))
                .filter(candidate -> SecurityFilterChain.class
                        .isAssignableFrom(candidate.getReturnType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no SecurityFilterChain bean method named " + beanMethodName));

        Order order = AnnotationUtils.findAnnotation(method, Order.class);
        return order == null ? Ordered.LOWEST_PRECEDENCE : order.value();
    }
}
