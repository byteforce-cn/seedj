package cn.byteforce.seedj.biz.annotation;

import cn.byteforce.seedj.biz.autoconfigure.BizEnablementConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the Biz capability: resource-server security, command template, audit,
 * event bus and observability wiring.
 *
 * <p>This annotation is only a feature switch (same contract as
 * {@code @EnableIamServer}). The application must still be a regular Spring Boot
 * application ({@code @SpringBootApplication}); the annotation does not imply
 * component scanning or application startup.</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(BizEnablementConfiguration.class)
public @interface EnableBizServer {
}
