package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.read.ReadGateway;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.abac.CompositeAuthorizationDecisionPoint;
import cn.byteforce.seedj.starter.iam.authz.IamContractAuthorizationService;
import cn.byteforce.seedj.starter.iam.autoconfigure.IamAbacConfiguration;
import cn.byteforce.seedj.starter.iam.read.IamFieldPolicyResolver;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;

/**
 * 授权收口的结构门禁：命令/读路径只认识决策点，ABAC 引擎不再是对外并行入口。
 *
 * <p>运行期装配断言在 IAM starter 的上下文测试里；这里固定源码级依赖，防止未来 为了“方便”又把 PDP 注入回命令或读路径。
 */
class AuthorizationWiringTest {

    @Test
    @DisplayName("CommandPolicy 的 API 不暴露 IamPolicyDecisionPoint")
    void commandPolicyOnlyKnowsDecisionPoint() {
        for (Method method : CommandPolicy.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                assertThat(parameter.getType().getName())
                        .as("CommandPolicy.%s", method.getName())
                        .doesNotContain("IamPolicyDecisionPoint");
            }
        }
    }

    @Test
    @DisplayName("ReadGateway 的生产构造器接收决策点而不是 PDP")
    void readGatewayTakesDecisionPoint() {
        Constructor<?> production =
                Arrays.stream(ReadGateway.class.getConstructors())
                        .filter(constructor -> constructor.getParameterCount() == 9)
                        .findFirst()
                        .orElseThrow();
        assertThat(production.getParameterTypes()[8]).isEqualTo(AuthorizationDecisionPoint.class);
        assertThat(
                        Arrays.stream(ReadGateway.class.getDeclaredFields())
                                .map(field -> field.getType().getName()))
                .noneMatch(name -> name.contains("IamPolicyDecisionPoint"));
    }

    @Test
    @DisplayName("字段策略解析器接收决策点")
    void fieldPolicyResolverTakesDecisionPoint() {
        assertThat(
                        Arrays.stream(IamFieldPolicyResolver.class.getConstructors())
                                .map(constructor -> constructor.getParameterTypes()[0]))
                .contains(AuthorizationDecisionPoint.class);
    }

    @Test
    @DisplayName("契约服务实现决策点；组合实现是唯一对外类型")
    void iamImplementsDecisionPoint() {
        assertThat(AuthorizationDecisionPoint.class)
                .isAssignableFrom(IamContractAuthorizationService.class);
        assertThat(AuthorizationDecisionPoint.class)
                .isAssignableFrom(CompositeAuthorizationDecisionPoint.class);
    }

    @Test
    @DisplayName("ABAC 引擎不再是 @Primary 并行入口")
    void policyDecisionPointIsNotPrimary() throws NoSuchMethodException {
        Method method =
                IamAbacConfiguration.class.getMethod(
                        "iamPolicyDecisionPoint",
                        IamPolicyRepository.class,
                        IamPolicyDecisionPoint.class,
                        IamProperties.class);
        assertThat(method.isAnnotationPresent(Primary.class)).isFalse();
    }
}
