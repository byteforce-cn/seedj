package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.domain.ObjectSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalDdl;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTable;
import cn.byteforce.seedj.module.ObjectModuleConfigurer;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.read.DataScopeResolver;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * pilot 的宿主侧夹具：调用方身份、授权判定、工单表的显式迁移，以及审计操作人。
 *
 * <p>四件事都只属于「宿主怎么接」而不是产品能力，所以不放进 {@code seedj-ai-starter}：
 *
 * <ul>
 *   <li><b>调用方</b>由固定 {@link CallerProvider} 给出：guard 用 {@code CallerIdCodec.uuid()} 把 {@code
 *       id} 解析成 token 里的 {@code actor}，用 {@code tenantId} 逐字比对 token 的 {@code tenant}——所以两者
 *       都必须是 UUID 字符串，且与 {@code seedj.ai.plane.tenant}/{@code identity.*} 是同一个值。
 *   <li><b>授权判定</b>一律放行：本 pilot 要证明的是决策 token 这道门（guard），seedj 自己的命令/对象权限层若也拦，就
 *       分不清是「决策面没签」还 是「授权没给」。这是夹具的取舍；生产上两层都要在。
 *   <li><b>工单表</b>用 {@code TemporalDdl} 从 {@link Ticket} 的 schema 建表：字段仍只有一个来源，迁移只在本进程启动时
 *       跑一次。
 *   <li><b>审计操作人</b>换成 agent principal：spec §6.3 要求链条目 {@code actor} = agent，而 dev-auth 把整个
 *       {@code Caller} 塞进认证主体，{@code AuditActor} 取到的是 {@code Caller.toString()}。夹具在安全链之后、guard
 *       之前把它换成 agent 的 UUID——链上因此能按 agent 归因。
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class PilotTestConfiguration {

    /** 决策面租户；与 {@code seedj.ai.plane.tenant} 同一个值。 */
    public static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");

    /** agent principal；与 {@code seedj.ai.identity.actor} 同一个值。 */
    public static final UUID AGENT_ID = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");

    /** 被代表的自然人；与 {@code seedj.ai.identity.on-behalf-of} 同一个值。 */
    public static final UUID USER_ID = UUID.fromString("018f2e3a-0000-7000-8000-000000000118");

    /** 任务契约标识；{@code seedj.ai.contracts[].id}，runtime 按 UUID 解析它。 */
    public static final String CONTRACT_ID = "018f2e3a-0000-7000-8000-00000000c017";

    /** 决策 token 的受众；与 {@code seedj.ai.decision.audience} 同一个值。 */
    public static final String AUDIENCE = "ticket-service";

    /**
     * 当前调用方：pilot 的 agent。
     *
     * @return 固定调用方
     */
    @Bean
    CallerProvider pilotCallerProvider() {
        Caller agent =
                new Caller(
                        AGENT_ID.toString(),
                        "pilot-agent",
                        TENANT.toString(),
                        Set.of("agent"),
                        Set.of(),
                        Set.of(),
                        Set.of(Caller.DEFAULT_AMR),
                        Caller.DEFAULT_ACR,
                        Map.of());
        return () -> Optional.of(agent);
    }

    /**
     * 授权判定：pilot 一律放行，把门留在决策 token 上（见类说明）。
     *
     * @return 放行的判定点
     */
    @Bean
    AuthorizationDecisionPoint pilotAuthorizationDecisionPoint() {
        return request -> AuthorizationDecision.allow("pilot-allows");
    }

    /**
     * 工单的行范围：全部可见。
     *
     * <p>「没有 resolver 的对象一律 403」是刻意的前置：行范围是 fail-closed 的，不写 resolver 就拿不到读写范围。
     *
     * @return 行范围贡献者
     */
    @Bean
    DataScopeResolver pilotTicketScopeResolver() {
        return new TicketScopeResolver();
    }

    /**
     * 工单模块声明。
     *
     * @param create 创建处理器
     * @param comment 评论处理器
     * @return 模块声明
     */
    @Bean
    ObjectModuleConfigurer pilotTicketModule(
            CreateTicketHandler create, CommentTicketHandler comment) {
        return new TicketModule(create, comment);
    }

    /**
     * 创建处理器。
     *
     * @param commandIds 目标对象 ID 生成器（biz 的 UUIDv7 生成器）
     * @return 处理器
     */
    @Bean
    CreateTicketHandler pilotCreateTicketHandler(
            @Qualifier("commandIdGenerator") IdGenerator<UUID> commandIds) {
        return new CreateTicketHandler(commandIds);
    }

    /**
     * 评论处理器。
     *
     * @return 处理器
     */
    @Bean
    CommentTicketHandler pilotCommentTicketHandler() {
        return new CommentTicketHandler();
    }

    /**
     * 工单表的显式迁移：从 {@link Ticket} 的 schema 生成建表 DDL，启动期执行一次。
     *
     * <p>与 HR 样板同一思路（{@code ObjectSchemaCapability}），只是 DDL 由 {@code TemporalDdl} 从 schema 生成：pilot
     * 没有「已发布的迁移不动」的历史包袱，字段的唯一来源因此仍是 record。
     *
     * @param dataSource 数据源
     * @param runners 迁移执行器
     * @param modules 模块注册表
     * @return 能力
     */
    @Bean
    InfrastructureCapability pilotTicketSchemaCapability(
            DataSource dataSource,
            ObjectProvider<JdbcMigrationRunner> runners,
            ObjectModuleRegistry modules) {
        ObjectSchema schema = modules.require("ticket").schema();
        return new ObjectSchemaCapability(
                "pilot-ticket",
                runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)),
                schema,
                List.of(
                        new Migration(
                                1,
                                "create pilot_ticket table",
                                TemporalDdl.createTable(TemporalTable.from(schema)))));
    }

    /**
     * 审计操作人：把认证主体换成 agent principal（见类说明）。
     *
     * <p>顺序取安全链之后（{@code springSecurityFilterChain} 在 {@code
     * SecurityFilterProperties.DEFAULT_FILTER_ORDER}）、guard 之前（guard = +10）：安全链先把 dev-auth 的调用方放进
     * context，这里读它、换掉主体名，后续命令事务里的 {@code AuditActor} 就取到 agent 的 UUID。
     *
     * @return 过滤器注册项
     */
    @Bean
    FilterRegistrationBean<AgentActorFilter> pilotAgentActorFilter() {
        FilterRegistrationBean<AgentActorFilter> registration =
                new FilterRegistrationBean<>(new AgentActorFilter());
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 5);
        registration.setUrlPatterns(List.of("/*"));
        return registration;
    }

    /** 把已认证主体换成 agent principal，让审计 actor 就是 agent（spec §6.3）。 */
    static final class AgentActorFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication current = SecurityContextHolder.getContext().getAuthentication();
            if (current != null) {
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                        AGENT_ID.toString(),
                                        null,
                                        current.getAuthorities()));
            }
            chain.doFilter(request, response);
        }
    }

    /** 工单的行范围：pilot 不做行级隔离，但仍然显式声明（没有 resolver 的读写一律 403）。 */
    static final class TicketScopeResolver implements DataScopeResolver {

        @Override
        public boolean supports(String objectType) {
            return "ticket".equals(objectType);
        }

        @Override
        public ScopePredicate resolve(
                String objectType, Caller caller, ScopeContext context) {
            return ScopePredicate.all();
        }
    }
}
