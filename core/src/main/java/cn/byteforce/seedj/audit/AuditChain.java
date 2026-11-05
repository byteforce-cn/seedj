package cn.byteforce.seedj.audit;

import cn.byteforce.seedj.audit.spi.AuditStore;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.id.IdGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 审计哈希链的权威实现。
 *
 * <p>本类只负责链的编排：分配序号、构造条目、计算哈希、写入存储、更新链头。
 * 存储细节委托给 {@link AuditStore}，事务边界委托给 Spring 集成层的装饰器，
 * 因此本类<b>不依赖</b>任何 Spring 注解或 JDBC 类型，可脱离容器单测。</p>
 *
 * <p><b>标识</b>：条目 ID 由注入的 {@link IdGenerator} 产生；{@code ID} 类型由
 * 适配器决定，与 {@link AuditEntry} / {@link AuditStore} 保持一致。本类不直接
 * 调用 {@code UUID.randomUUID()} 或任何具体生成算法。</p>
 *
 * <p><b>写入语义</b>：</p>
 * <ul>
 *   <li><b>串行写</b>：由 {@link AuditStore#nextSequenceAndPrevHash()} 在调用方事务内
 *       持锁分配序号，并发命令在审计处串行、提交后序号连续无缺口；</li>
 *   <li><b>同事务</b>：本类<b>不管理事务</b>。{@link #append} 要求调用方已开启事务，
 *       生产环境应通过 {@code TransactionalAuditAppender} 装饰后注入，由装饰器强制
 *       {@code MANDATORY} 语义；命令回滚则审计一并回滚；</li>
 *   <li>链头即锚行：{@code last_hash} 恒等于最新条目哈希；空链时 = 创世前序哈希。</li>
 * </ul>
 *
 * <p><b>验证</b>：{@link #verify()} 全链重算（语义与 {@code verify.tool} 一致）；
 * <b>锚定</b>：{@link #anchorNow()} 经 {@link AuditAnchor} 导出链头。</p>
 *
 * @param <ID> 审计标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public class AuditChain<ID> implements AuditAppender<ID> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final AuditStore<ID> store;

    private final Clock clock;

    private final AuditAnchor anchor;

    private final IdGenerator<ID> idGenerator;

    /**
     * 构造审计链。
     *
     * @param store       审计存储端口（由适配器模块提供）
     * @param clock       时钟
     * @param anchor      锚定实现
     * @param idGenerator ID 生成器
     */
    public AuditChain(AuditStore<ID> store, Clock clock, AuditAnchor anchor,
                      IdGenerator<ID> idGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /**
     * 追加一条审计条目。
     *
     * <p><b>事务要求</b>：调用方必须已开启事务，且本方法的三次存储写操作与该事务同生共死。
     * 本类不自行开事务；生产装配使用 {@code TransactionalAuditAppender} 强制该契约。</p>
     *
     * @param request 追加请求
     */
    @Override
    public void append(AuditAppendRequest<ID> request) {
        AuditStore.Head head = store.nextSequenceAndPrevHash();
        if (head == null) {
            throw new IllegalStateException("audit store returned null head");
        }
        long sequence = head.getSequence();
        String prevHash = head.getHash();
        JsonNode payload = request.getPayload() == null ? MAPPER.createObjectNode() : request.getPayload();
        // 全链路 UTC 微秒（T-R007）：纳秒截断为微秒后再入哈希/落库，保证回读重算一致
        Instant timestamp = clock.now().truncatedTo(ChronoUnit.MICROS);
        ID id = idGenerator.id();
        if (id == null) {
            throw new IllegalStateException("audit id generator returned null");
        }

        AuditEntry<ID> unsigned = new AuditEntry<>(id, sequence, timestamp, request.getActor(),
                request.getAction(), request.getResourceType(), request.getResourceId(), payload, null, prevHash,
                null);
        String hash = CanonicalHasher.computeHash(unsigned);
        AuditEntry<ID> entry = new AuditEntry<>(id, sequence, timestamp, request.getActor(),
                request.getAction(), request.getResourceType(), request.getResourceId(), payload, hash, prevHash,
                null);

        store.insert(entry);
        store.updateHead(hash);
    }

    /**
     * 全链验证（语义与 {@code verify.tool} 一致）。
     *
     * @return 错误列表（空 = 通过）
     */
    public List<AuditVerifier.AuditViolation> verify() {
        return AuditVerifier.verify(loadEntries());
    }

    /**
     * 读取全链条目（按 sequence 升序）。
     *
     * @return 审计条目列表
     */
    public List<AuditEntry<ID>> loadEntries() {
        return store.loadAll();
    }

    /**
     * 读取链头快照（锚行）。
     *
     * @return 链头（含当前时刻）
     */
    public AuditHead head() {
        AuditStore.Head head = store.head();
        if (head == null) {
            throw new IllegalStateException("audit store returned null head");
        }
        return new AuditHead(head.getSequence(), head.getHash(), clock.now());
    }

    /**
     * 立即锚定链头（运维/定时任务动作）。
     *
     * <p>不开启事务包裹外部锚定调用，避免长时间持有数据库事务。</p>
     *
     * @return 已锚定的链头快照
     */
    public AuditHead anchorNow() {
        AuditHead head = head();
        anchor.anchor(head);
        return head;
    }
}
