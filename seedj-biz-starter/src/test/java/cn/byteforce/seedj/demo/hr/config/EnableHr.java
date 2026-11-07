package cn.byteforce.seedj.demo.hr.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * 开启 HR 模块（员工入职）。
 *
 * <p>显式开关而不是组件扫描：宿主应用能一眼看出"这个模块需要 EventPublisher"， 而不是在启动失败时才从堆栈里发现。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(HrConfiguration.class)
public @interface EnableHr {}
