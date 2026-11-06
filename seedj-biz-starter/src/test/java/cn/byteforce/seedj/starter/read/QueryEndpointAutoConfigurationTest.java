package cn.byteforce.seedj.starter.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.module.ObjectModuleConfigurer;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.read.DataScopeResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 类型化查询面：路由生成、权限、参数解析与视图映射。
 */
class QueryEndpointAutoConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Caller READER = new Caller("u-1", "alice", "default", Set.of(),
            Set.of("hr.employee.read", "hr.employee.query"), Set.of(), Set.of("pwd"),
            "urn:seedj:acr:pwd", Map.of());

    private static final Caller READER_WITHOUT_QUERY_PERMISSION = new Caller("u-2", "bob",
            "default", Set.of(), Set.of("hr.employee.read"), Set.of(), Set.of("pwd"),
            "urn:seedj:acr:pwd", Map.of());

    private static final ObjectModules MODULES = modules();

    private static final ObjectModuleRegistry REGISTRY = MODULES.freeze();

    private static final ObjectSchema SCHEMA = REGISTRY.require("employee").schema();

    private final InMemoryTemporalStore store = new InMemoryTemporalStore(() -> NOW,
            new ObjectSchemaRegistry(List.of(SCHEMA)));

    private final AtomicReference<Caller> caller = new AtomicReference<>(READER);

    private final WebApplicationContextRunner runner = runnerWith(modules ->
            modules.object("employee", "hr_employee")
                    .readPermission("hr.employee.read")
                    .field("employee_no", FieldType.TEXT).required()
                    .filterable().sortable()
                    .field("name", FieldType.TEXT).filterable()
                    .query(EmployeeView.class)
                    .path("/api/hr/employees")
                    .permission("hr.employee.query")
                    .sort("employee_no"));

    @Test
    @DisplayName("没有 query 声明的应用仍能启动，路由为空")
    void contextWithoutQueriesStarts() {
        runnerWith(modules -> modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("employee_no", FieldType.TEXT).required())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RouterFunction<ServerResponse> routes = context.getBean("moduleQueryRoutes",
                            RouterFunction.class);
                    MockHttpServletRequest request = new MockHttpServletRequest("GET",
                            "/api/hr/employees");
                    assertThat(routes.route(ServerRequest.create(request, List.of())))
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("查询路由走网关，过滤、分页与视图映射一次完成")
    void queryRouteUsesGatewayAndMapsView() {
        UUID alice = append("E-1", "Alice");
        append("E-2", "Bob");

        runner.run(context -> {
            ServerResponse response = call(context, Map.of("name", "Alice"));

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
            QueryEndpointAutoConfiguration.QueryPageResponse body = body(response);
            assertThat(body.total()).isEqualTo(1);
            assertThat(body.page()).isEqualTo(1);
            assertThat(body.size()).isEqualTo(20);
            assertThat(body.items()).singleElement().satisfies(item -> {
                EmployeeView view = (EmployeeView) item;
                assertThat(view.id()).isEqualTo(alice);
                assertThat(view.version()).isEqualTo(1);
                assertThat(view.employeeNo()).isEqualTo("E-1");
                assertThat(view.name()).isEqualTo("Alice");
            });
        });
    }

    @Test
    @DisplayName("未认证调用返回 401，缺查询权限返回 403")
    void callerAndPermissionAreEnforced() {
        caller.set(null);
        runner.run(context -> assertThatThrownBy(() -> call(context, Map.of()))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.UNAUTHENTICATED));

        caller.set(READER_WITHOUT_QUERY_PERMISSION);
        runner.run(context -> assertThatThrownBy(() -> call(context, Map.of()))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("非法 page/size/direction/as_of 是无效载荷，不进入存储")
    void invalidParametersAreRejected() {
        runner.run(context -> {
            assertInvalid(context, Map.of("page", "0"), "page");
            assertInvalid(context, Map.of("size", "201"), "size");
            assertInvalid(context, Map.of("size", "abc"), "size");
            assertInvalid(context, Map.of("direction", "sideways"), "direction");
            assertInvalid(context, Map.of("as_of", "not-an-instant"), "as_of");
            assertInvalid(context, Map.of("recorded_as_of", "2026-13-01"), "recorded_as_of");
        });
    }

    @Test
    @DisplayName("未知过滤字段由 schema 拒绝，不静默忽略")
    void unknownFilterIsRejected() {
        runner.run(context -> assertInvalid(context, Map.of("unknown", "x"), "unknown"));
    }

    private WebApplicationContextRunner runnerWith(ObjectModuleConfigurer configurer) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObjectModuleAutoConfiguration.class,
                        ReadAutoConfiguration.class, QueryEndpointAutoConfiguration.class))
                .withBean(Clock.class, () -> (Clock) () -> NOW)
                .withBean(InMemoryTemporalStore.class, () -> store)
                .withBean(CallerProvider.class, () -> (CallerProvider) () ->
                        Optional.ofNullable(caller.get()))
                .withBean(DataScopeResolver.class, QueryEndpointAutoConfigurationTest::allScope)
                .withBean(ObjectModuleConfigurer.class, () -> configurer);
    }

    private static void assertInvalid(org.springframework.context.ApplicationContext context,
                                      Map<String, String> params, String name) {
        assertThatThrownBy(() -> call(context, params))
                .isInstanceOf(ContractException.class)
                .hasMessageContaining(name)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
    }

    @SuppressWarnings("unchecked")
    private static ServerResponse call(org.springframework.context.ApplicationContext context,
                                       Map<String, String> params) throws Exception {
        RouterFunction<ServerResponse> routes = context.getBean("moduleQueryRoutes",
                RouterFunction.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/hr/employees");
        params.forEach(request::addParameter);
        ServerRequest serverRequest = ServerRequest.create(request, List.of());
        HandlerFunction<ServerResponse> handler = routes.route(serverRequest).orElseThrow();
        return handler.handle(serverRequest);
    }

    private static QueryEndpointAutoConfiguration.QueryPageResponse body(ServerResponse response) {
        assertThat(response).isInstanceOf(EntityResponse.class);
        return (QueryEndpointAutoConfiguration.QueryPageResponse)
                ((EntityResponse<?>) response).entity();
    }

    private UUID append(String employeeNo, String name) {
        UUID id = UUID.randomUUID();
        store.append(AppendRequest.create("employee", id, NOW, null, ACTOR,
                Map.of("employee_no", employeeNo, "name", name)));
        return id;
    }

    private static ObjectModules modules() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("employee_no", FieldType.TEXT).required().filterable().sortable()
                .field("name", FieldType.TEXT).filterable();
        return modules;
    }

    private static DataScopeResolver allScope() {
        return new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return true;
            }

            @Override
            public ScopePredicate resolve(String objectType, Caller caller,
                                          cn.byteforce.seedj.core.security.ScopeContext context) {
                return ScopePredicate.all();
            }
        };
    }

    /** 测试视图。 */
    record EmployeeView(UUID id, long version, String employeeNo, String name) {
    }
}
