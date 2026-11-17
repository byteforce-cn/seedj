package cn.byteforce.seedj.starter.im;

/**
 * 成员展示名解析：默认返回 subject，宿主覆盖这个 bean 即可接 IAM 用户目录。
 *
 * <p>解析发生在成员列表的读路径上，一次请求对每个成员调用一次；实现应有缓存，避免逐条远程调用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface ImDisplayNameResolver {

    /**
     * 解析展示名。
     *
     * @param subjectId IAM subject
     * @return 展示名；不认识的 subject 应返回原值而不是 {@code null}
     */
    String displayName(String subjectId);
}
