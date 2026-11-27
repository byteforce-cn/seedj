package cn.byteforce.seedj.file;

import java.io.InputStream;
import java.util.Optional;

/**
 * 文件能力入口，同时是 provider 实现契约。
 *
 * <h2>语义</h2>
 *
 * <ul>
 *   <li><b>create-only</b>：{@link #put} 只在 key 不存在时成功；同一个 key 已存在抛
 *       {@link FileErrorCode#ALREADY_EXISTS}，不覆盖。需要替换时调用方先 {@link #delete}；
 *   <li>key 规则由 {@link FileKeys#requireValid(String)} 定义，所有 provider 一致；
 *   <li>内容按流读取，但长度由 {@code contentLength} 显式给出：流只能恰好提供这么多字节，
 *       短读或长读都抛 {@link FileErrorCode#INVALID_REQUEST}；
 *   <li>空内容（{@code contentLength == 0}）不支持，抛 {@link FileErrorCode#INVALID_REQUEST}；
 *   <li>provider 不支持的能力（列举、预签名 URL、覆盖）不进入本接口；需要时用独立的可选接口扩展，
 *       核心四个方法保持所有 provider 可实现。
 * </ul>
 *
 * <h2>它不保证什么</h2>
 *
 * <ul>
 *   <li>不保证流式落盘：当前 coord 实现会按 {@code contentLength} 整块缓冲，超过 provider 上限时
 *       在写入前抛 {@link FileErrorCode#TOO_LARGE}；
 *   <li>不提供一致性之外的目录语义：key 里的 {@code /} 只是普通字符，没有目录列举/移动；
 *   <li>不提供跨 provider 的 {@code version} 格式：它是实现相关的不可透明字符串（coord 是 revision，
 *       S3 是 ETag）。
 * </ul>
 *
 * <p>实现必须线程安全。{@code FileService} 不拥有需要关闭的客户端资源；客户端生命周期由装配层管理。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface FileService {

    /**
     * 写入一个新文件。
     *
     * @param key 逻辑 key，规则见 {@link FileKeys#requireValid(String)}
     * @param contentType MIME 类型，可为 {@code null}
     * @param contentLength 内容字节数，必须大于 0 且与 {@code content} 精确一致
     * @param content 内容流；本方法只读取 {@code contentLength} 字节，不关闭流
     * @return 写入后的元数据
     * @throws FileException {@code INVALID_KEY} / {@code INVALID_REQUEST} / {@code TOO_LARGE} /
     *     {@code ALREADY_EXISTS} / {@code PROVIDER_UNAVAILABLE} / {@code INTERNAL_ERROR}
     */
    FileMetadata put(String key, String contentType, long contentLength, InputStream content);

    /**
     * 打开文件内容。
     *
     * @param key 逻辑 key
     * @return 内容流；调用方负责关闭
     * @throws FileException {@code INVALID_KEY} / {@code NOT_FOUND} / {@code PROVIDER_UNAVAILABLE} /
     *     {@code INTERNAL_ERROR}
     */
    InputStream open(String key);

    /**
     * 读取元数据。
     *
     * @param key 逻辑 key
     * @return 元数据；不存在返回 {@link Optional#empty()}
     * @throws FileException {@code INVALID_KEY} / {@code PROVIDER_UNAVAILABLE} / {@code INTERNAL_ERROR}
     */
    Optional<FileMetadata> stat(String key);

    /**
     * 删除文件。
     *
     * <p>先摘除 key 的映射（线性化点），再尽力清理底层数据；底层清理失败最多留下不可达的孤儿数据，
     * 不影响本方法的返回值。
     *
     * @param key 逻辑 key
     * @return 本次是否删除了映射；不存在返回 {@code false}
     * @throws FileException {@code INVALID_KEY} / {@code PROVIDER_UNAVAILABLE} / {@code INTERNAL_ERROR}
     */
    boolean delete(String key);
}
