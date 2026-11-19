package cn.byteforce.seedj.ai.spi;

import cn.byteforce.seedj.ai.ExternalEffect;
import cn.byteforce.seedj.ai.ExternalEffectState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 外部效果存储端口：{@code prepare} 幂等，{@code complete} 只前进。
 *
 * <p>同 decision 同命令/参数：{@code prepare} 返回既有（{@code CONFIRMED} 直接返回，调用方不再调外部系统）； 字段不同抛 {@link
 * IllegalStateException}（消息含 {@code external effect conflict}）。{@code complete} 对未知 decision 抛
 * {@link IllegalArgumentException}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ExternalEffectStore {

    /**
     * 固化一次外部调用意图；已确认的直接返回既有。
     *
     * @param effect PREPARED 效果
     * @return 落库后的效果
     */
    ExternalEffect prepare(ExternalEffect effect);

    /**
     * 写入完成状态。
     *
     * @param decisionId 决策标识
     * @param state {@code CONFIRMED} / {@code FAILED} / {@code UNKNOWN}
     * @param externalRef 外部回执；{@code CONFIRMED} 时必填
     * @param errorCode 错误码；{@code FAILED} 时必填
     * @param now 完成时间
     * @return 更新后的效果
     */
    ExternalEffect complete(
            UUID decisionId,
            ExternalEffectState state,
            String externalRef,
            String errorCode,
            Instant now);

    /**
     * 按决策标识查询。
     *
     * @param decisionId 决策标识
     * @return 效果；不存在为空
     */
    Optional<ExternalEffect> find(UUID decisionId);
}
