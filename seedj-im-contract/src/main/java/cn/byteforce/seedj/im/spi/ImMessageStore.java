package cn.byteforce.seedj.im.spi;

import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.ImMessageDraft;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息存储端口：只追加、按序号读。
 *
 * <p>实现约定：
 *
 * <ul>
 *   <li>{@link #append(ImMessageDraft)} 以 {@code (conversationId, senderId, clientMsgId)} 去重：
 *       已存在时返回既有消息，不新增行、不改序号。
 *   <li>序号冲突（同一会话并发取到同一号）抛可重试的 {@code ContractException(VERSION_CONFLICT)}； 调用方应换号重试一次。
 *   <li>正文大小上限由调用方（starter）保证，存储不做截断。
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ImMessageStore {

    /**
     * 追加一条消息。
     *
     * @param draft 待写入的消息
     * @return 落库后的消息（幂等命中时是既有消息）
     */
    ImMessage append(ImMessageDraft draft);

    /**
     * 按标识查询。
     *
     * @param id 消息标识
     * @return 消息；不存在为空
     */
    Optional<ImMessage> find(UUID id);

    /**
     * 按客户端幂等键查询。
     *
     * @param conversationId 会话标识
     * @param senderId 发送者
     * @param clientMsgId 客户端幂等键
     * @return 消息；不存在为空
     */
    Optional<ImMessage> findByClientMsgId(UUID conversationId, String senderId, String clientMsgId);

    /**
     * 会话内增量拉取：返回 {@code seq > afterSeq} 的消息，按序号升序。
     *
     * @param conversationId 会话标识
     * @param afterSeq 游标（不含）
     * @param limit 单页上限
     * @return 消息列表
     */
    List<ImMessage> page(UUID conversationId, long afterSeq, int limit);

    /**
     * 跨会话增量拉取：给定会话集合里 {@code seq > afterSeq} 的消息，按序号升序。
     *
     * <p>这是多端同步与离线补拉的唯一读路径。会话集合由调用方从 {@link ImConversationStore#conversationsOfSubject}
     * 取得——成员判断留在会话聚合里，消息存储 只做范围扫描，两套实现都不需要跨表/跨 map 依赖。
     *
     * @param conversationIds 目标会话集合；空集合返回空列表
     * @param afterSeq 游标（不含）
     * @param limit 单页上限
     * @return 消息列表
     */
    List<ImMessage> sync(List<UUID> conversationIds, long afterSeq, int limit);

    /**
     * 会话内最大的消息序号。
     *
     * @param conversationId 会话标识
     * @return 最大序号；无消息时为 {@code 0}
     */
    long maxSeq(UUID conversationId);

    /**
     * 会话内序号大于游标的消息条数，用于未读数。
     *
     * @param conversationId 会话标识
     * @param afterSeq 游标（不含）
     * @return 条数
     */
    long countAfter(UUID conversationId, long afterSeq);

    /**
     * 编辑消息正文：序号、发送者与创建时间不变，写入 {@code edited_at}。
     *
     * <p>重复编辑是允许的：以最后一次写入为准，{@code edited_at} 前进。已撤回的消息不可编辑—— 撤回是终态，改正文会让客户端看到「被撤回的内容变了」。
     *
     * @param id 消息标识
     * @param body 新消息体
     * @return 编辑后的消息
     * @throws cn.byteforce.seedj.core.exception.ContractException 消息不存在（404）或已撤回（400）
     */
    ImMessage edit(UUID id, Map<String, Object> body);

    /**
     * 撤回消息：只写 {@code recalled_at}，正文保留在库里（审计与争议取证），读路径负责清空。
     *
     * <p>幂等：已撤回的消息原样返回，{@code recalled_at} 不前进。
     *
     * @param id 消息标识
     * @return 撤回后的消息
     * @throws cn.byteforce.seedj.core.exception.ContractException 消息不存在（404）
     */
    ImMessage recall(UUID id);

    /**
     * 删除早于给定时刻的消息，最多 {@code limit} 条，用于保留期清理。
     *
     * @param cutoff 截止时刻（不含）：{@code created_at < cutoff} 的行被删除
     * @param limit 单次删除上限；非正值是无效载荷
     * @return 实际删除条数
     */
    int deleteBefore(Instant cutoff, int limit);
}
