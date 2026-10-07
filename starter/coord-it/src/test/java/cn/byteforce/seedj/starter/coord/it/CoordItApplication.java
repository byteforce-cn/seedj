package cn.byteforce.seedj.starter.coord.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 集成测试用的最小 Spring Boot 应用。
 *
 * <p>以「业务应用引入 coord-starter 一族」的方式加载完整自动配置链路
 * （AutoConfiguration.imports → 各模块自动配置 → 子客户端 / 注册 / 配置 / LB 装配），
 * 确保验证的是真实消费路径而非直接 new 出来的 SDK 对象。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@SpringBootApplication
class CoordItApplication {
}
