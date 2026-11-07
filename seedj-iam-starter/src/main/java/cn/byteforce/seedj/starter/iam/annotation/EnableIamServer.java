package cn.byteforce.seedj.starter.iam.annotation;

import cn.byteforce.seedj.starter.iam.autoconfigure.IamEnablementConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Enables the IAM server capability.
 *
 * <p>This annotation is only a feature switch. The application must still be a regular Spring Boot
 * application ({@code @SpringBootApplication}); the annotation no longer implies component scanning
 * or application startup.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(IamEnablementConfiguration.class)
public @interface EnableIamServer {}
