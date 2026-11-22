package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.im.ImEnabledMarker;
import cn.byteforce.seedj.im.spi.ImCardActionStore;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.starter.im.protocol.body.AckReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import cn.byteforce.seedj.starter.im.protocol.body.ConversationListReq;
import cn.byteforce.seedj.starter.im.protocol.body.EditReq;
import cn.byteforce.seedj.starter.im.protocol.body.MemberListReq;
import cn.byteforce.seedj.starter.im.protocol.body.OfflinePullReq;
import cn.byteforce.seedj.starter.im.protocol.body.PresenceReq;
import cn.byteforce.seedj.starter.im.protocol.body.ReadReceiptReq;
import cn.byteforce.seedj.starter.im.protocol.body.RecallReq;
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncReq;
import cn.byteforce.seedj.starter.im.protocol.body.TypingReq;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * IM 命令装配：会话/消息服务与内置命令字处理器。
 *
 * <p>默认策略 bean 全部 {@code @ConditionalOnMissingBean}，宿主覆盖即可接入自己的组织模型； 事务与 outbox
 * 都按可用性装配（没有就用直通/丢弃），不把「本地没数据库」变成启动失败。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(
        after = ImStoreAutoConfiguration.class,
        afterName = "cn.byteforce.seedj.starter.cqrs.wiring.CommandAgentAutoConfiguration")
@ConditionalOnBean(ImEnabledMarker.class)
@ConditionalOnProperty(
        prefix = "seedj.im",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties({ImProperties.class, ImSystemProperties.class})
public class ImCommandAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ImConversationPolicy imConversationPolicy() {
        return new ImConversationPolicy() {};
    }

    @Bean
    @ConditionalOnMissingBean
    ImConversationService imConversationService(
            ImConversationStore conversations,
            ImAuthorizationGate gate,
            ImConversationPolicy policy,
            Clock clock) {
        return new ImConversationService(conversations, gate, policy, clock);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    ImSessionExecutor imSessionExecutor(ImProperties properties) {
        return new ImSessionExecutor(
                properties.executor().threads(), properties.executor().queueCapacity());
    }

    @Bean
    @ConditionalOnMissingBean
    ImMessagingService imMessagingService(
            ImConversationService conversationService,
            ImConversationStore conversations,
            ImMessageStore messages,
            ImCursorStore cursors,
            ImSequenceAllocator numbers,
            ImPushProvider push,
            ImConversationPolicy policy,
            ObjectProvider<TransactionRunner> transactions,
            ObjectProvider<OutboxWriter> outbox,
            ImCardActionStore cardActions,
            ImCardActionRegistry cardHandlers,
            ImProperties properties) {
        return new ImMessagingService(
                conversationService,
                conversations,
                messages,
                cursors,
                numbers,
                push,
                policy,
                transactions.getIfAvailable(TransactionRunner::direct),
                outbox.getIfAvailable(OutboxWriter::discard),
                properties,
                cardActions,
                cardHandlers);
    }

    @Bean
    @ConditionalOnMissingBean
    ImCardActionRegistry imCardActionRegistry(ObjectProvider<ImCardActionHandler> handlers) {
        return new ImCardActionRegistry(handlers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean(name = "imChatSendHandler")
    ImCommandHandler<ChatSendReq> imChatSendHandler(ImMessagingService messaging) {
        return new ImBuiltInHandlers.Send(messaging);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imChatGroupSendHandler")
    ImCommandHandler<ChatSendReq> imChatGroupSendHandler(ImMessagingService messaging) {
        return new ImBuiltInHandlers.SendGroup(messaging);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imAckHandler")
    ImCommandHandler<AckReq> imAckHandler(ImMessagingService messaging) {
        return new ImBuiltInHandlers.Ack(messaging);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imReadReceiptHandler")
    ImCommandHandler<ReadReceiptReq> imReadReceiptHandler(ImMessagingService messaging) {
        return new ImBuiltInHandlers.Read(messaging);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imOfflinePullHandler")
    ImCommandHandler<OfflinePullReq> imOfflinePullHandler(ImMessagingService messaging) {
        return new ImBuiltInHandlers.Pull(messaging);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imSessionSyncHandler")
    ImCommandHandler<SessionSyncReq> imSessionSyncHandler(ImMessagingService messaging) {
        return new ImBuiltInHandlers.Sync(messaging);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imRecallHandler")
    ImCommandHandler<RecallReq> imRecallHandler(ImMessagingService messaging) {
        return new ImBuiltInHandlers.Recall(messaging);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imEditHandler")
    ImCommandHandler<EditReq> imEditHandler(ImMessagingService messaging) {
        return new ImBuiltInHandlers.Edit(messaging);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imConversationListHandler")
    ImCommandHandler<ConversationListReq> imConversationListHandler(
            ImConversationService conversations, ImMessagingService messaging) {
        return new ImBuiltInHandlers.Conversations(conversations, messaging);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imMemberListHandler")
    ImCommandHandler<MemberListReq> imMemberListHandler(
            ImConversationService conversations, ImDisplayNameResolver names) {
        return new ImBuiltInHandlers.Members(conversations, names);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imTypingHandler")
    ImCommandHandler<TypingReq> imTypingHandler(ImTypingService typing) {
        return new ImBuiltInHandlers.Typing(typing);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imPresenceHandler")
    ImCommandHandler<PresenceReq> imPresenceHandler(ImPresenceService presence) {
        return new ImBuiltInHandlers.Presence(presence);
    }

    /**
     * 成员展示名默认实现：返回 subject。宿主覆盖这个 bean 接入 IAM 用户目录。
     *
     * @return 解析器
     */
    @Bean
    @ConditionalOnMissingBean
    ImDisplayNameResolver imDisplayNameResolver() {
        return subjectId -> subjectId;
    }

    /**
     * 在线状态服务；没有传输层（也就没有 {@link ChannelRegistry}）时用空注册表，查询恒为离线。
     *
     * @param registry 连接注册表
     * @param conversations 会话存储
     * @param push 投递端口
     * @return 在线状态服务
     */
    @Bean
    @ConditionalOnMissingBean
    ImPresenceService imPresenceService(
            ObjectProvider<ChannelRegistry> registry,
            ImConversationStore conversations,
            ImPushProvider push) {
        return new ImPresenceService(
                registry.getIfAvailable(ChannelRegistry::new), conversations, push);
    }

    /**
     * 输入中服务。
     *
     * @param conversations 会话服务
     * @param store 会话存储
     * @param push 投递端口
     * @param clock 取时入口
     * @return 输入中服务
     */
    @Bean
    @ConditionalOnMissingBean
    ImTypingService imTypingService(
            ImConversationService conversations,
            ImConversationStore store,
            ImPushProvider push,
            Clock clock) {
        return new ImTypingService(conversations, store, push, clock);
    }

    /**
     * 保留期清理：{@code seedj.im.message.retention} 为空时不启动调度。
     *
     * @param messages 消息存储
     * @param properties IM 配置
     * @param clock 取时入口
     * @return 清理任务
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    ImRetentionCleaner imRetentionCleaner(
            ImMessageStore messages, ImProperties properties, Clock clock) {
        return new ImRetentionCleaner(messages, properties, clock);
    }

    /**
     * 单聊授权默认实现：拒绝给自己发消息（其余交给宿主覆盖）。
     *
     * @return 闸门
     */
    @Bean
    @ConditionalOnMissingBean
    ImAuthorizationGate imDefaultAuthorizationGate() {
        return (Caller caller, String peerId) -> peerId != null && !peerId.equals(caller.id());
    }
}
