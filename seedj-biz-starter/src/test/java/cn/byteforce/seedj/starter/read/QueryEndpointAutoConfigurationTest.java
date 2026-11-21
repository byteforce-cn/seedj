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
import cn.byteforce.seedj.starter.domain.web.ObjectPageResponse;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/** 类型化查询面：路由生成、权限、参数解析与视图映射。 */
class QueryEndpointAutoConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Caller READER =
            new Caller(
                    "u-1",
                    "alice",
                    "default",
                    Set.of(),
                    Set.of("hr.employee.read", "hr.employee.query"),
                    Set.of(),
                    Set.of("pwd"),
                    "urn:seedj:acr:pwd",
                    Map.of());

    private static final Caller READER_WITHOUT_QUERY_PERMISSION =
            new Caller(
                    "u-2",
                    "bob",
                    "default",
                    Set.of(),
                    Set.of("hr.employee.read"),
                    Set.of(),
                    Set.of("pwd"),
                    "urn:seedj:acr:pwd",
                    Map.of());

    private static final ObjectModules MODULES = modules();

    private static final ObjectModuleRegistry REGISTRY = MODULES.freeze();

    private static final ObjectSchema SCHEMA = REGISTRY.require("employee").schema();

    private final InMemoryTemporalStore store =
            new InMemoryTemporalStore(() -> NOW, new ObjectSchemaRegistry(List.of(SCHEMA)));

    private final AtomicReference<Caller> caller = new AtomicReference<>(READER);

    private final WebApplicationContextRunner runner =
            runnerWith(
                    modules ->
                            modules.object("employee", "hr_employee")
                                    .readPermission("hr.employee.read")
                                    .field("employee_no", FieldType.TEXT)
                                    .required()
                                    .filterable()
                                    .sortable()
                                    .field("name", FieldType.TEXT)
                                    .filterable()
                                    .query(EmployeeView.class)
                                    .path("/api/hr/employees")
                                    .permission("hr.employee.query")
                                    .sort("employee_no"));

    @Test
    @DisplayName("没有 query 声明的应用仍能启动，路由为空")
    void contextWithoutQueriesStarts() {
        runnerWith(
                        modules ->
                                modules.object("employee", "hr_employee")
                                        .readPermission("hr.employee.read")
                                        .field("employee_no", FieldType.TEXT)
                                        .required())
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            RouterFunction<ServerResponse> routes =
                                    context.getBean("moduleQueryRoutes", RouterFunction.class);
                            MockHttpServletRequest request =
                                    new MockHttpServletRequest("GET", "/api/hr/employees");
                            assertThat(routes.route(ServerRequest.create(request, List.of())))
                                    .isEmpty();
                        });
    }

    @Test
    @DisplayName("查询路由走网关，过滤、分页与视图映射一次完成")
    void queryRouteUsesGatewayAndMapsView() {
        UUID alice = append("E-1", "Alice");
        append("E-2", "Bob");

        runner.run(
                context -> {
                    ServerResponse response = call(context, Map.of("name", "Alice"));

                    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
                    QueryEndpointAutoConfiguration.QueryPageResponse body = body(response);
                    assertThat(body.total()).isEqualTo(1);
                    assertThat(body.page()).isEqualTo(1);
                    assertThat(body.size()).isEqualTo(20);
                    assertThat(body.items())
                            .singleElement()
                            .satisfies(
                                    item -> {
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
        runner.run(
                context ->
                        assertThatThrownBy(() -> call(context, Map.of()))
                                .isInstanceOf(ContractException.class)
                                .extracting(error -> ((ContractException) error).code())
                                .isEqualTo(ContractErrorCode.UNAUTHENTICATED));

        caller.set(READER_WITHOUT_QUERY_PERMISSION);
        runner.run(
                context ->
                        assertThatThrownBy(() -> call(context, Map.of()))
                                .isInstanceOf(ContractException.class)
                                .extracting(error -> ((ContractException) error).code())
                                .isEqualTo(ContractErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("非法 page/size/direction/as_of 是无效载荷，不进入存储")
    void invalidParametersAreRejected() {
        runner.run(
                context -> {
                    assertInvalid(context, Map.of("page", "0"), "page");
                    assertInvalid(context, Map.of("size", "201"), "size");
                    assertInvalid(context, Map.of("size", "abc"), "size");
                    assertInvalid(context, Map.of("direction", "sideways"), "direction");
                    assertInvalid(context, Map.of("as_of", "not-an-instant"), "as_of");
                    assertInvalid(
                            context, Map.of("recorded_as_of", "2026-13-01"), "recorded_as_of");
                });
    }

    @Test
    @DisplayName("未知过滤字段由 schema 拒绝，不静默忽略")
    void unknownFilterIsRejected() {
        runner.run(context -> assertInvalid(context, Map.of("unknown", "x"), "unknown"));
    }

    @Test
    @DisplayName("声明式查询面接受 sort/direction（与通用面同一套保留字）")
    void declarativeSurfaceAcceptsSort() {
        append("E-1", "Alice");
        append("E-2", "Bob");

        runner.run(
                context -> {
                    ServerResponse response =
                            call(context, Map.of("sort", "employee_no", "direction", "DESC"));

                    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(body(response).total()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("列表查询拒绝 min_version 与注入字符")
    void listRejectsMinVersionAndInjection() {
        runner.run(
                context -> {
                    assertInvalid(context, Map.of("min_version", "2"), "min_version");
                    assertInvalid(context, Map.of("name", "x' OR 1=1"), "illegal characters");
                    assertInvalid(context, Map.of("name__nope", "x"), "unknown filter operator");
                });
    }

    @ParameterizedTest
    @MethodSource("parityParams")
    @DisplayName("同一请求在两个读入口得到相同的状态语义")
    void bothSurfacesAgree(Map<String, String> params) {
        append("E-1", "Alice");
        append("E-2", "Bob");

        runner.run(
                context -> {
                    Object generic = genericSurface(context, params);
                    Object declarative = declarativeSurface(context, params);
                    if (generic instanceof ContractException genericError) {
                        assertThat(declarative)
                                .isInstanceOfSatisfying(
                                        ContractException.class,
                                        error ->
                                                assertThat(error.code())
                                                        .isEqualTo(genericError.code()));
                        return;
                    }
                    assertThat(declarative).isInstanceOf(QueryEndpointAutoConfiguration.QueryPageResponse.class);
                    ObjectPageResponse genericPage = (ObjectPageResponse) generic;
                    QueryEndpointAutoConfiguration.QueryPageResponse declarativePage =
                            (QueryEndpointAutoConfiguration.QueryPageResponse) declarative;
                    assertThat(declarativePage.total()).isEqualTo(genericPage.total());
                    assertThat(declarativePage.page()).isEqualTo(genericPage.page());
                    assertThat(declarativePage.size()).isEqualTo(genericPage.size());
                    assertThat(declarativePage.items()).hasSize(genericPage.items().size());
                });
    }

    static java.util.stream.Stream<Map<String, String>> parityParams() {
        return java.util.stream.Stream.of(
                Map.of(),
                Map.of("sort", "employee_no", "direction", "DESC"),
                Map.of("page", "1", "size", "1"),
                Map.of("name", "Alice"),
                Map.of("name__in", "Alice,Bob"),
                Map.of("unknown", "x"),
                Map.of("min_version", "2"),
                Map.of("page", "abc"),
                Map.of("name", "x' OR 1=1"),
                Map.of("name__nope", "x"));
    }

    private static Object genericSurface(
            org.springframework.context.ApplicationContext context, Map<String, String> params) {
        try {
            return new cn.byteforce.seedj.starter.domain.web.ObjectQueryController(
                            context.getBean(cn.byteforce.seedj.read.ReadGateway.class),
                            context.getBean(CallerProvider.class))
                    .list("employee", params);
        } catch (ContractException error) {
            return error;
        }
    }

    private static Object declarativeSurface(
            org.springframework.context.ApplicationContext context, Map<String, String> params) {
        try {
            ServerResponse response = call(context, params);
            return ((EntityResponse<?>) response).entity();
        } catch (ContractException error) {
            return error;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private WebApplicationContextRunner runnerWith(ObjectModuleConfigurer configurer) {
        return new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                ObjectModuleAutoConfiguration.class,
                                ReadAutoConfiguration.class,
                                QueryEndpointAutoConfiguration.class))
                .withBean(Clock.class, () -> (Clock) () -> NOW)
                .withBean(InMemoryTemporalStore.class, () -> store)
                .withBean(
                        CallerProvider.class,
                        () -> (CallerProvider) () -> Optional.ofNullable(caller.get()))
                .withBean(DataScopeResolver.class, QueryEndpointAutoConfigurationTest::allScope)
                .withBean(
                        AuthorizationDecisionPoint.class,
                        () -> request -> AuthorizationDecision.allow("test"))
                .withBean(ObjectModuleConfigurer.class, () -> configurer);
    }

    private static void assertInvalid(
            org.springframework.context.ApplicationContext context,
            Map<String, String> params,
            String name) {
        assertThatThrownBy(() -> call(context, params))
                .isInstanceOf(ContractException.class)
                .hasMessageContaining(name)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
    }

    @SuppressWarnings("unchecked")
    private static ServerResponse call(
            org.springframework.context.ApplicationContext context, Map<String, String> params)
            throws Exception {
        RouterFunction<ServerResponse> routes =
                context.getBean("moduleQueryRoutes", RouterFunction.class);
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
        store.append(
                AppendRequest.create(
                        "employee",
                        id,
                        NOW,
                        null,
                        ACTOR,
                        Map.of("employee_no", employeeNo, "name", name)));
        return id;
    }

    private static ObjectModules modules() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("employee_no", FieldType.TEXT)
                .required()
                .filterable()
                .sortable()
                .field("name", FieldType.TEXT)
                .filterable();
        return modules;
    }

    private static DataScopeResolver allScope() {
        return new DataScopeResolver() {
            @Override
            public boolean supports(String objectType) {
                return true;
            }

            @Override
            public ScopePredicate resolve(
                    String objectType,
                    Caller caller,
                    cn.byteforce.seedj.core.security.ScopeContext context) {
                return ScopePredicate.all();
            }
        };
    }

    /** 测试视图。 */
    record EmployeeView(UUID id, long version, String employeeNo, String name) {}
}
