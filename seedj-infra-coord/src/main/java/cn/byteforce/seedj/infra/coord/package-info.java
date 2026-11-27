/**
 * coord 基础设施实现：ID、编号、全局序列、EventBus、锁、PKI、Transit、策略判定与文件服务。
 *
 * <p><b>职责</b>：把 coord 0.4.3 的 Java SDK 接到 seedj 的能力契约上；只交付库与 Spring 自动配置， 不启动 coord
 * server、不产出可执行制品。
 *
 * <p><b>依赖方向</b>：依赖 {@code core} 契约与 {@code coord-java-sdk}；禁止依赖 JDBC、starter 与宿主模块。 业务持久化见 {@code
 * seedj-jdbc-postgresql}。
 */
package cn.byteforce.seedj.infra.coord;
