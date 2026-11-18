package cn.byteforce.seedj.starter.ai.annotation;

import cn.byteforce.seedj.starter.ai.AiEnablementConfiguration;
import cn.byteforce.seedj.starter.autoconfigure.BizEnablementConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * 一键启用 AI 能力：决策账本、DecisionToken、执行侧 guard、决策面与有界 agent 循环。
 *
 * <p>同时引入 biz 标记（命令、审计、安全等业务能力依赖它）；应用仍需是普通 Spring Boot 应用， 注解只做能力开关，不隐含组件扫描或启动逻辑。
 *
 * <p>能力 bean 全部由 {@code AiEnabledMarker} 门控，标记只在本注解被使用时注册：模块被宿主依赖但宿主没写
 * 本注解时，AI 能力一个都不会装配。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({BizEnablementConfiguration.class, AiEnablementConfiguration.class})
public @interface EnableAIServer {}
