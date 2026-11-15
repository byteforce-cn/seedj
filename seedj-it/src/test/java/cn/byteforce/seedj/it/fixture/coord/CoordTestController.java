package cn.byteforce.seedj.it.fixture.coord;

import cn.byteforce.seedj.core.clock.spi.GlobalSequencer;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.numbering.Numbering;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅测试用的 coord 能力探测端点：暴露实际装配的 provider 类型，并提供 ID / 编号 / 全局序列 / 锁的可调用入口。
 *
 * <p>锁的租约按服务实例保存在本控制器里：一个服务持锁、另一个服务争抢，才能验证跨进程互斥。非 coord 模式启动时， 缺失的能力返回 {@code none}，调用时由测试自己跳过。
 */
@RestController
@RequestMapping("/api/public/test/coord")
public class CoordTestController {

    private final ObjectProvider<IdGenerator<String>> ids;
    private final ObjectProvider<Numbering> numbering;
    private final ObjectProvider<GlobalSequencer> sequencer;
    private final ObjectProvider<LockProvider> locks;
    private final ObjectProvider<EventBusTransport> transports;
    private final ObjectProvider<EventConsumerStore> stores;

    /** 编号探针的固定 scope：端点不接受外部输入，避免把测试控制器变成注入入口。 */
    private static final String NUMBERING_SCOPE = "it_scope";

    /** 本实例当前持有的锁租约；key 与请求里的 key 一致。 */
    private final Map<String, LockLease> leases = new ConcurrentHashMap<>();

    public CoordTestController(
            ObjectProvider<IdGenerator<String>> ids,
            ObjectProvider<Numbering> numbering,
            ObjectProvider<GlobalSequencer> sequencer,
            ObjectProvider<LockProvider> locks,
            ObjectProvider<EventBusTransport> transports,
            ObjectProvider<EventConsumerStore> stores) {
        this.ids = ids;
        this.numbering = numbering;
        this.sequencer = sequencer;
        this.locks = locks;
        this.transports = transports;
        this.stores = stores;
    }

    /** 实际装配的实现类名：Coord* 才说明走了 coord provider，而不是本地实现。 */
    @GetMapping("/providers")
    public Map<String, Object> providers() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("idGenerator", name(ids.getIfAvailable()));
        result.put("numbering", name(numbering.getIfAvailable()));
        result.put("globalSequencer", name(sequencer.getIfAvailable()));
        result.put("lockProvider", name(locks.getIfAvailable()));
        result.put("eventBusTransport", name(transports.getIfAvailable()));
        result.put("eventConsumerStore", name(stores.getIfAvailable()));
        return result;
    }

    @GetMapping("/id")
    public Map<String, Object> id() {
        return Map.of("id", require(ids.getIfAvailable(), "IdGenerator").id());
    }

    @GetMapping("/number")
    public Map<String, Object> number() {
        return Map.of(
                "value", require(numbering.getIfAvailable(), "Numbering").next(NUMBERING_SCOPE));
    }

    @GetMapping("/sequence")
    public Map<String, Object> sequence() {
        return Map.of("value", require(sequencer.getIfAvailable(), "GlobalSequencer").next());
    }

    /** 尝试获取并持有租约；进程退出/显式释放前保持占有。 */
    @PostMapping("/lock/acquire")
    public Map<String, Object> acquire(
            @RequestParam String key, @RequestParam(defaultValue = "30000") long ttlMillis) {
        LockProvider provider = require(locks.getIfAvailable(), "LockProvider");
        LockLease lease = provider.tryAcquire(key, Duration.ofMillis(ttlMillis)).orElse(null);
        if (lease != null) {
            leases.put(key, lease);
        }
        return Map.of("acquired", lease != null);
    }

    /** 释放本实例持有的租约；未持有视为成功（幂等）。 */
    @PostMapping("/lock/release")
    public Map<String, Object> release(@RequestParam String key) {
        LockLease lease = leases.remove(key);
        if (lease != null) {
            lease.close();
        }
        return Map.of("released", true);
    }

    private static String name(Object provider) {
        return provider == null ? "none" : provider.getClass().getSimpleName();
    }

    private static <T> T require(T provider, String type) {
        if (provider == null) {
            throw new IllegalStateException(type + " bean is not available in this service");
        }
        return provider;
    }
}
