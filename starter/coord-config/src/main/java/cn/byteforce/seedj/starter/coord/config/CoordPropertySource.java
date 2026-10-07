package cn.byteforce.seedj.starter.coord.config;

import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * coord 配置的可变属性源：启动期由 {@code CoordConfigDataLoader} 填充快照，
 * 运行期由 {@link CoordConfigRefreshManager} 按 Watch 事件原地更新
 * （属性名 = 存储 key 原文，不做剥离 / 重写；约定 C2）。
 *
 * <p>线程安全：底层为 {@link ConcurrentHashMap}；watching 回调线程写、业务线程读。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordPropertySource extends MapPropertySource {

    private final List<String> prefixes;

    /**
     * @param name     属性源名（建议 {@code coord:<prefixes>}，展示于 /actuator/env）
     * @param prefixes 纳入管理的 key 前缀（约定 C2 的过滤语义）
     */
    public CoordPropertySource(String name, List<String> prefixes) {
        super(name, new ConcurrentHashMap<>());
        this.prefixes = List.copyOf(prefixes);
    }

    /**
     * 按前缀集合创建（属性源名自动生成）。
     *
     * @param prefixes key 前缀
     * @return 空的属性源
     */
    public static CoordPropertySource forPrefixes(List<String> prefixes) {
        return new CoordPropertySource("coord:" + String.join(",", prefixes), prefixes);
    }

    /**
     * 管理的前缀（导入时声明）。
     *
     * @return 不可变前缀列表
     */
    public List<String> prefixes() {
        return prefixes;
    }

    /**
     * 应用一次变更（Watch 事件或快照合并）。
     *
     * @param key      key 原文
     * @param newValue 新值；{@link Optional#empty()} 表示删除（约定 C2，待上游核实）
     * @return true 表示值发生变化（含新增与删除）
     */
    public boolean apply(String key, Optional<String> newValue) {
        if (newValue.isEmpty()) {
            return getSource().remove(key) != null;
        }
        Object previous = getSource().put(key, newValue.get());
        return !newValue.get().equals(previous);
    }
}
