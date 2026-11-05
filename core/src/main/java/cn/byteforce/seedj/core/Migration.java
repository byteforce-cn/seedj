package cn.byteforce.seedj.core;

import java.util.Objects;

/**
 * 一条版本化迁移条目，供各数据层适配器复用。
 *
 * <p>只描述「版本 + 描述 + 内容」，不绑定任何方言、事务、锁或执行框架：
 * <ul>
 *   <li>{@code content} 的语义由执行器解释——SQL 适配层解释为 DDL 文本，
 *       文档型适配层可解释为 JSON/脚本；</li>
 *   <li>{@code version} 必须单调递增，一旦发布不可复用、不可修改内容；</li>
 *   <li>{@code description} 仅用于人类阅读，不参与 checksum 和顺序判定。</li>
 * </ul>
 *
 * <p>{@code content} 为文本类型；二进制迁移不在本类型能力范围内。
 */
public record Migration(int version, String description, String content) {

    public Migration {
        if (version < 1) {
            throw new IllegalArgumentException("migration version must be >= 1, got: " + version);
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(content, "content");
        if (content.isBlank()) {
            throw new IllegalArgumentException("migration content must not be blank");
        }
    }
}
