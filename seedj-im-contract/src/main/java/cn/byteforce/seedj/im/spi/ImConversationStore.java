package cn.byteforce.seedj.im.spi;

import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImMember;
import cn.byteforce.seedj.im.ImMemberRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话聚合的存储端口：会话本身、成员任期与成员变更事件。
 *
 * <p>三个方法族放在一个端口里是刻意的——它们是同一个事务边界内的同一个聚合，拆开只会让调用方 必须知道「先写哪个」。
 *
 * <p>实现约定：
 *
 * <ul>
 *   <li>成员变更与事件写入必须在同一事务内完成；事件序号从 {@link ImSequenceAllocator} 取。
 *   <li>{@link #create(ImConversation)} 遇到同租户同名 {@code directKey} 已存在时抛 {@code
 *       ContractException(IDEMPOTENCY_CONFLICT)}，不得静默返回另一条会话。
 *   <li>{@link #addMember} 对已在群内的成员是幂等的：不新增任期、不消耗序号。
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ImConversationStore {

    /**
     * 创建会话，并写入一条 {@code created} 事件（{@code actorId} 取 {@code ownerId}）。
     *
     * @param conversation 会话快照
     * @return 落库后的会话
     */
    ImConversation create(ImConversation conversation);

    /**
     * 按标识查询会话。
     *
     * @param id 会话标识
     * @return 会话；不存在为空
     */
    Optional<ImConversation> find(UUID id);

    /**
     * 按单聊去重键查询会话。
     *
     * @param tenantId 租户
     * @param directKey 去重键
     * @return 会话；不存在为空
     */
    Optional<ImConversation> findByDirectKey(String tenantId, String directKey);

    /**
     * 某主体当前所属会话，按最后更新时间倒序分页。
     *
     * @param tenantId 租户
     * @param subjectId IAM subject
     * @param page 分页请求
     * @return 一页会话
     */
    Page<ImConversation> pageOfSubject(String tenantId, String subjectId, PageRequest page);

    /**
     * 某主体当前所属的全部会话（不分页），按最后更新时间倒序。
     *
     * @param tenantId 租户
     * @param subjectId IAM subject
     * @return 会话列表
     */
    List<ImConversation> conversationsOfSubject(String tenantId, String subjectId);

    /**
     * 改群名，并写入一条 {@code renamed} 事件。
     *
     * @param conversationId 会话标识
     * @param title 新群名
     * @param actorId 发起人（写进事件与审计）
     */
    void rename(UUID conversationId, String title, String actorId);

    /**
     * 加人：新增一段成员任期，并写入一条 {@code member_added} 事件。
     *
     * @param conversationId 会话标识
     * @param subjectId 被加入的 subject
     * @param role 角色
     * @param actorId 发起人（写进事件与审计）
     */
    void addMember(UUID conversationId, String subjectId, ImMemberRole role, String actorId);

    /**
     * 退群（或被移除）：关闭当前任期，并写入一条 {@code member_removed} 事件。
     *
     * @param conversationId 会话标识
     * @param subjectId 退群的 subject
     * @param actorId 发起人（写进事件与审计）
     */
    void removeMember(UUID conversationId, String subjectId, String actorId);

    /**
     * 查询某会话当前任期的成员。
     *
     * @param conversationId 会话标识
     * @return 成员列表；不含已退群的人
     */
    List<ImMember> members(UUID conversationId);

    /**
     * 查询某会话在给定序号时有效的成员——回答「那时谁在群里」。
     *
     * @param conversationId 会话标识
     * @param seq 时点序号
     * @return 成员列表
     */
    List<ImMember> membersAt(UUID conversationId, long seq);

    /**
     * 查询某主体在某会话的当前任期。
     *
     * @param conversationId 会话标识
     * @param subjectId 主体
     * @return 任期；不在群里为空
     */
    Optional<ImMember> member(UUID conversationId, String subjectId);
}
