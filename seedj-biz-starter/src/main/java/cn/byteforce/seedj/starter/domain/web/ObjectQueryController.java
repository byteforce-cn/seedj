package cn.byteforce.seedj.starter.domain.web;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.read.ReadGateway;
import cn.byteforce.seedj.starter.web.ObjectQueryParameters;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用对象查询 HTTP 面（internal surface，默认关闭）。
 *
 * <p>参数解析与声明式查询面共用 {@link ObjectQueryParameters}；所有读取都经 {@link ReadGateway}，对象权限、行范围、字段策略与审计不降级。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public final class ObjectQueryController {

    private final ReadGateway gateway;

    private final CallerProvider callers;

    /**
     * 构造控制器。
     *
     * @param gateway 读网关
     * @param callers 当前调用方
     */
    public ObjectQueryController(ReadGateway gateway, CallerProvider callers) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.callers = Objects.requireNonNull(callers, "callers");
    }

    /**
     * 按 ID 查询对象。
     *
     * @param objectType 对象类型
     * @param id 对象 ID
     * @param asOf 业务时点（ISO-8601）
     * @param recordedAsOf 系统时点（ISO-8601）
     * @param minVersion 最小对象版本
     * @return 对象响应
     */
    @GetMapping("/objects/{objectType}/{id}")
    public ObjectQueryResponse find(
            @PathVariable String objectType,
            @PathVariable UUID id,
            @RequestParam(value = "as_of", required = false) String asOf,
            @RequestParam(value = "recorded_as_of", required = false) String recordedAsOf,
            @RequestParam(value = "min_version", required = false) String minVersion) {
        TemporalRow row =
                gateway.find(
                                objectType,
                                id,
                                new TemporalQuery(
                                        ObjectQueryParameters.asOf(asOf),
                                        ObjectQueryParameters.recordedAsOf(recordedAsOf),
                                        ObjectQueryParameters.minVersion(minVersion)),
                                caller())
                        .orElseThrow(
                                () ->
                                        new ContractException(
                                                ContractErrorCode.OBJECT_NOT_FOUND,
                                                "object not found"));
        return ObjectQueryResponse.from(row);
    }

    /**
     * 列表查询：受限过滤器（{@code field__op=...}）+ keyset 分页 + 排序 + 时点。
     *
     * <p>普通 {@code field=value} 保持等值兼容；{@code field__in=v1,v2}、 {@code field__gte=...}、{@code
     * field__between=a,b}、{@code field__like=...} 等只在 schema 声明为 filterable 时生效。含引号/分号/注释片段的值直接
     * 400。
     *
     * @param objectType 对象类型
     * @param params 查询参数
     * @return 一页对象
     */
    @GetMapping("/objects/{objectType}")
    public ObjectPageResponse list(
            @PathVariable String objectType, @RequestParam Map<String, String> params) {
        Page<TemporalRow> result =
                gateway.search(
                        objectType,
                        ObjectQueryParameters.parse(params).toSearch(objectType, null),
                        caller());
        return ObjectPageResponse.from(result, row -> row);
    }

    private Caller caller() {
        return callers.find()
                .orElseThrow(
                        () ->
                                new ContractException(
                                        ContractErrorCode.UNAUTHENTICATED, "caller is required"));
    }
}
