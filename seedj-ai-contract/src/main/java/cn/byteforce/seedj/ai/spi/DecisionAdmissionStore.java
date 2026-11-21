package cn.byteforce.seedj.ai.spi;

import cn.byteforce.seedj.ai.DecisionAdmission;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 准入凭证的存储端口。
 *
 * <p>实现约定：
 *
 * <ul>
 *   <li>{@link #admit} 同 {@code decisionId} 同字段幂等；不同命令/参数/主体/过期时间抛 {@link IllegalStateException}
 *       （消息包含 {@code admission conflict}），不覆盖首次准入；
 *   <li>{@link #find} 不存在为空；{@link #deleteExpired} 只删 {@code expiresAt < before} 的行。
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface DecisionAdmissionStore {

    /**
     * 写入准入凭证；重复提交同字段是幂等的。
     *
     * @param admission 准入凭证
     * @throws IllegalArgumentException 凭证为空
     * @throws IllegalStateException 同决策已存在不同凭证
     */
    void admit(DecisionAdmission admission);

    /**
     * 按决策标识查询准入凭证。
     *
     * @param decisionId 决策标识
     * @return 凭证；不存在为空
     */
    Optional<DecisionAdmission> find(UUID decisionId);

    /**
     * 删除已过期的准入凭证。
     *
     * @param before 截止时间（不含）
     * @return 删除行数
     */
    int deleteExpired(Instant before);
}
