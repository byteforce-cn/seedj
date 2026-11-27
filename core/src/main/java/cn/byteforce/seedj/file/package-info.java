/**
 * 文件能力契约：与存储供应商无关的文件读写（create-only、流式入参、元数据）。
 *
 * <p><b>职责</b>：定义 {@link cn.byteforce.seedj.file.FileService} 的语义、错误码与 key 规则； 具体实现在 {@code
 * seedj-infra-coord}（coord 对象存储）或未来的 MinIO / OSS 适配器。
 *
 * <p><b>依赖方向</b>：只用 JDK；不依赖 Spring、监控框架或其它 seedj 能力。业务方按 key 读写， 不接触 provider 的 bucket / objectId /
 * revision。
 */
package cn.byteforce.seedj.file;
