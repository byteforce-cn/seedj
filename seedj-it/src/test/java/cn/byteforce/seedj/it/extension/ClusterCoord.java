package cn.byteforce.seedj.it.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入测试用 {@code CoordClient}（连到 {@link EnableCoordCluster} 启动的 agent）。
 *
 * <p>用于直接断言 coord 侧状态（topic/消息/消费组），验证被测服务的发布与消费确实落在 coord 上。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ClusterCoord {}
