package cn.byteforce.seedj.starter.coord.ratelimiter;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CoordRateLimit} 注解拦截器：按方法声明解析限流键并申请许可；
 * 被拒时抛出 {@link CoordRateLimitExceededException}（不静默、不改写调用语义）。
 *
 * <p>key 解析：包含 {@code #} 时按 SpEL 求值（参数名可引用）；否则为字面量。
 * 表达式按方法缓存解析结果（线程安全）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordRateLimitInterceptor implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CoordRateLimitInterceptor.class);

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final CoordRateLimiter rateLimiter;
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * @param rateLimiter 限流 API
     */
    public CoordRateLimitInterceptor(CoordRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        CoordRateLimit annotation = invocation.getMethod().getAnnotation(CoordRateLimit.class);
        if (annotation == null) {
            // 单次捕获 getThis()：避免二次调用不一致导致的分析器 NPE 告警/真实竞态面
            Object target = invocation.getThis();
            if (target != null) {
                annotation = org.springframework.core.annotation.AnnotatedElementUtils
                        .findMergedAnnotation(target.getClass(), CoordRateLimit.class);
            }
        }
        if (annotation == null) {
            return invocation.proceed();
        }
        String key = resolveKey(annotation.key(), invocation);
        if (!rateLimiter.tryAcquire(key, annotation.permits())) {
            log.debug("限流拒绝: key={}, method={}", key, invocation.getMethod());
            throw new CoordRateLimitExceededException(key, annotation.permits());
        }
        return invocation.proceed();
    }

    private String resolveKey(String keyExpression, MethodInvocation invocation) {
        if (!keyExpression.contains("#")) {
            return keyExpression;
        }
        Method method = invocation.getMethod();
        String cacheKey = method.toGenericString() + "::" + keyExpression;
        Expression expression = expressionCache.computeIfAbsent(cacheKey, ignored -> PARSER.parseExpression(keyExpression));
        EvaluationContext context = new MethodBasedEvaluationContext(
                invocation.getThis(), method, invocation.getArguments(), PARAMETER_NAME_DISCOVERER);
        String value = expression.getValue(context, String.class);
        return value == null ? keyExpression : value;
    }
}
