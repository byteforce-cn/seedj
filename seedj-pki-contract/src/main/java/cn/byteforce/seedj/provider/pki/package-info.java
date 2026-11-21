/**
 * PKI 能力契约。
 *
 * <p><b>职责：</b>证书签发（托管密钥 / CSR 外部密钥）、Transit 签名与验签、吊销与 CRL、按 serial 读证书、issuer 查询、alias →
 * 身份的稳定映射，以及 TLS 配置描述。
 *
 * <p><b>依赖方向：</b>只依赖 {@code core} 的异常/错误码与 {@code observability}；不依赖 Spring、Coord SDK 或任何具体
 * provider。实现放在 {@code seedj-infra-coord} / {@code seedj-infra-local}。
 *
 * <p><b>边界：</b>契约不持私钥、不落本地吊销账本。托管密钥的签名只能经 {@link cn.byteforce.seedj.provider.pki.PkiProvider#sign}
 * 完成；吊销与 CRL 的权威事实在 CA 侧。
 */
package cn.byteforce.seedj.provider.pki;
