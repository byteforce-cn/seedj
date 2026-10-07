package cn.byteforce.seedj.starter.coord;

import java.util.function.Supplier;

/**
 * Coord CCT 凭据来源 SPI。
 *
 * <p>注册为 Spring Bean 后会被自动配置用作 SDK 的
 * {@code CoordConfig.Builder#authTokenSupplier}：每次 RPC 调用读取当前 token，
 * 凭据轮换无需重建 gRPC channel。</p>
 *
 * <p>未提供时回退到 {@code byteforce.coord.auth-token} 静态值（如有）。
 * SDK 的 {@code Supplier<String>} 因泛型擦除无法按类型注入，故用本接口作为
 * 可注入的特化类型。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface CoordAuthTokenSupplier extends Supplier<String> {
}
