package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.starter.autoconfigure.BizEnablementConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * 一键启用 IM 长连接：TCP/WebSocket 传输、JWT 握手与连接注册表。
 *
 * <p>同时引入 biz 标记（命令、审计、安全等业务能力依赖它）；应用仍需是普通 Spring Boot 应用，
 * 注解不隐含组件扫描或启动逻辑。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({BizEnablementConfiguration.class, ImEnablementConfiguration.class})
public @interface EnableImServer {}
