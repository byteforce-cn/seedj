/**
 * 文件能力的 coord 适配器：数据对象 + {@code sha256(scope, key)} 定位的元数据对象。
 *
 * <p><b>职责</b>：把 {@link cn.byteforce.seedj.file.FileService} 映射到 coord 对象存储；元数据 放在同一 bucket
 * 的对象里，不依赖 coord KV/Txn（v1 对象存储要求 {@code kv_t} 关闭，而 SDK 的 Txn 是 v2）。
 *
 * <p><b>依赖方向</b>：只依赖 coord SDK 与 core 的文件契约；不依赖其它 seedj 能力。
 */
package cn.byteforce.seedj.infra.coord.file;
