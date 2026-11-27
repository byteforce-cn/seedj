package cn.byteforce.seedj.file;

import java.time.Instant;

/**
 * 文件元数据。
 *
 * @param key 逻辑 key
 * @param size 内容字节数
 * @param contentType MIME 类型；provider 不保存时为 {@code null}
 * @param version 实现相关的不可透明版本（coord revision / S3 ETag）；不支持时为 {@code null}
 * @param createdAt 创建时间；provider 不保存时为 {@code null}
 * @author ByteForce
 * @since 1.0.0
 */
public record FileMetadata(
        String key, long size, String contentType, String version, Instant createdAt) {}
