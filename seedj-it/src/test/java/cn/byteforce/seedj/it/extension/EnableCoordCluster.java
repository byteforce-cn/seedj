package cn.byteforce.seedj.it.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在集群 IT 中启用真 coord（server + agent 容器），并把 ID、编号、全局序列、锁、EventBus 发布/消费切到 coord provider。
 *
 * <p>容器夹具只在 Linux + Docker 下可用；不满足时整个测试类标记为跳过，而不是降级到本地实现。PKI/Transit 仍由 {@code seedj-infra-local}
 * 提供——coord 侧的适配器尚未交付（见 coord 设计 P2）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnableCoordCluster {}
