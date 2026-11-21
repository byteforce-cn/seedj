package cn.byteforce.seedj.starter.read;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.QueryDeclaration;
import cn.byteforce.seedj.read.ReadGateway;
import cn.byteforce.seedj.starter.web.ObjectQueryParameters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 为每个 {@code .query(...)} 声明生成 GET 端点，统一走 {@link ReadGateway}。
 *
 * <p>参数解析与通用对象面共用 {@link ObjectQueryParameters}：保留字、过滤、分页、时点、 排序与游标只有一个实现。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(after = ReadAutoConfiguration.class)
@ConditionalOnClass(RouterFunction.class)
@ConditionalOnBean({ReadGateway.class, ObjectModuleRegistry.class, CallerProvider.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QueryEndpointAutoConfiguration {

    /**
     * 模块查询路由。
     *
     * @param registry 模块注册表
     * @param gateway 读网关
     * @param callers 当前调用方
     * @param viewMapper 视图映射
     * @return 路由
     */
    @Bean
    @ConditionalOnMissingBean(name = "moduleQueryRoutes")
    public RouterFunction<ServerResponse> moduleQueryRoutes(
            ObjectModuleRegistry registry,
            ReadGateway gateway,
            CallerProvider callers,
            ViewMapper viewMapper) {
        List<QueryDeclaration> queries = registry.queries();
        if (queries.isEmpty()) {
            return request -> java.util.Optional.empty();
        }
        RouterFunctions.Builder builder = RouterFunctions.route();
        for (QueryDeclaration query : queries) {
            builder.GET(
                    query.path(), request -> handle(query, request, gateway, callers, viewMapper));
        }
        return builder.build();
    }

    /**
     * 默认视图映射。
     *
     * @return 映射器
     */
    @Bean
    @ConditionalOnMissingBean
    public ViewMapper viewMapper() {
        return new ViewMapper();
    }

    private static ServerResponse handle(
            QueryDeclaration query,
            ServerRequest request,
            ReadGateway gateway,
            CallerProvider callers,
            ViewMapper viewMapper) {
        Caller caller =
                callers.find()
                        .orElseThrow(
                                () ->
                                        new ContractException(
                                                ContractErrorCode.UNAUTHENTICATED,
                                                "caller is required"));
        if (!caller.hasPermission(query.permission())) {
            throw new ContractException(
                    ContractErrorCode.FORBIDDEN, "missing query permission: " + query.permission());
        }
        Page<TemporalRow> result =
                gateway.search(
                        query.objectType(),
                        ObjectQueryParameters.parse(params(request))
                                .toSearch(query.objectType(), query.sortField()),
                        caller);
        List<Object> items =
                result.items().stream().map(row -> viewMapper.map(row, query.viewType())).toList();
        return ServerResponse.ok()
                .body(new QueryPageResponse(items, result.page(), result.size(), result.total()));
    }

    private static Map<String, String> params(ServerRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        request.params()
                .forEach(
                        (name, values) ->
                                params.put(name, values.isEmpty() ? null : values.get(0)));
        return params;
    }

    /** 查询响应。 */
    public record QueryPageResponse(List<Object> items, int page, int size, long total) {}
}
