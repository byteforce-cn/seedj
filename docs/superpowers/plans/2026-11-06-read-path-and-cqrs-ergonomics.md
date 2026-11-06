# 读路径纵深与 CQRS 开发体验 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让读路径和写路径一样只有一个带权限的入口，并把一个实体的手写代码从 11 个文件压到 6 个文件以内。

**Architecture:** 新增 `ReadGateway` 作为唯一读入口，行范围用受限 `ScopePredicate` 下推到 `TemporalStore`，字段隐私按 `Privacy` 层级 + 策略 clearance 渲染为 full/masked/hidden。写路径用 `ObjectModuleConfigurer` 声明实体，每个命令一个显式 `CommandHandler`，`CommandRuntime` 继续收口幂等/锁/事务/outbox/审计。评估中的 P0 一并落地。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Jackson 3（`tools.jackson`）、JUnit 5、AssertJ、Maven Wrapper。

**Spec:** `docs/superpowers/specs/2026-11-06-read-path-and-cqrs-ergonomics-design.md`

## Global Constraints

- `core` 零 Spring；模型/SPI 在 core，Spring 装配在 starter。
- 不新增 Maven module；新包落在既有模块（`core`、`seedj-biz-starter`、`seedj-iam-starter`、`seedj-infra-local`、`seedj-jdbc-postgresql`、`seedj-server/hr`、`seedj-it`）。
- 不保留向前兼容：删除 deprecated 构造器、两个 `append` 重载、旧 `ObjectQueryController` 构造器。
- 单租户：任何 SQL/谓词/过滤都不得出现 tenant；`seedj.biz.tenant-id` 删除；`Caller.tenantId` 固定 `Caller.DEFAULT_TENANT`。
- 分页与 `total` 必须在数据库内按 scope 过滤后再计数；禁止先分页后内存过滤。
- 无 scope resolver → 403；resolver/context 异常 → 503；不允许默认放行。
- 策略编译失败在启动期抛出；不允许运行期退回“先查后过滤”。
- 测试默认离线：`./mvnw -o ...`；最终门禁 `bash config/ci/verify.sh`。
- 每个任务结束必须：目标模块测试绿、`./mvnw -o -B spotless:apply` 后无格式差异。
- 覆盖率下限：`core` 52.8/42.7、`seedj-biz-starter` 60.1/51.5；新增代码行覆盖 ≥ 80%。

## Review Focus

1. **未声明 resolver 的对象读**：必须 403，且不产生任何 `TemporalStore` 调用；不能返回空列表冒充成功。
2. **无法编译的策略**：字段对字段、未声明字段、未声明 context key、不支持算子必须在启动期失败，不能放宽成“全部可见”。
3. **`Not` + NULL**：`NOT(field = x)` 在字段为 NULL 时必须匹配（二值语义），不能变成空集或全集。
4. **`IN` 超过 1000 / ScopeContext 重复 key**：必须显式失败，不截断、不后者覆盖。
5. **幂等重放与崩溃恢复**：重放不调用 `targetId`、不写事件；lease 过期后同 key 可重试。

---

## File Structure

```text
core/src/main/java/cn/byteforce/seedj/
  domain/
    ScopePredicate.java            谓词 AST（sealed）
    ScopePredicates.java           组合、内存求值、字段校验
    Privacy.java                   字段隐私层级
    Masker.java / Maskers.java     打码扩展点与默认实现
    FieldDefinition.java           字段声明（+sortable/indexed/privacy/masker）
    TemporalStore.java             读方法强制带 ScopePredicate
  core/security/
    ScopeKey.java / ScopeKeyType.java / ScopeKeyCatalog.java
    ScopeContext.java              请求事实快照
    ScopeContextHolder.java        ThreadLocal scope
    ContextSink.java / ContextWriter.java / ContextResolver.java
    DefaultContextResolver.java
  read/
    ReadModel.java / ReadModelRegistry.java
    DataScopeResolver.java / FieldPolicyResolver.java
    FieldAccess.java / FieldPolicy.java
    ReadAccessEvent.java / ReadAuditSink.java
    ReadGateway.java
  module/
    ObjectModuleConfigurer.java
    ObjectModules.java             DSL（schema/read/query/command）
    ObjectModuleRegistry.java      冻结后的注册表（实现 ReadModelRegistry）
  cqrs/
    CommandHandler.java
    CommandContext.java
    AbstractCommandHandler.java
    CommandRegistration.java
    WriteScopeProvider.java
    CommandRuntime.java            改为执行 handler
    CommandCatalog.java            从注册表构造

core/src/test/java/cn/byteforce/seedj/...
seedj-infra-local/.../InMemoryTemporalStore.java
seedj-jdbc-postgresql/.../ScopeSql.java, PostgresTemporalStore.java, TemporalDdl.java, TemporalTable.java
seedj-biz-starter/.../starter/read/*
seedj-iam-starter/.../starter/iam/read/*
seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/*
seedj-it/src/test/java/cn/byteforce/seedj/it/*
```

---

## Milestone 1：读路径契约

### Task 1: `ScopePredicate` 与内存求值

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/domain/ScopePredicate.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/ScopePredicates.java`
- Test: `core/src/test/java/cn/byteforce/seedj/domain/ScopePredicateTest.java`

**Interfaces:**
- Produces:
  ```java
  public sealed interface ScopePredicate {
      record All() implements ScopePredicate {}
      record None() implements ScopePredicate {}
      record Eq(String field, Object value) implements ScopePredicate {}
      record In(String field, List<?> values) implements ScopePredicate {}
      record NotNull(String field) implements ScopePredicate {}
      record And(List<ScopePredicate> parts) implements ScopePredicate {}
      record Or(List<ScopePredicate> parts) implements ScopePredicate {}
      record Not(ScopePredicate part) implements ScopePredicate {}

      static ScopePredicate all();
      static ScopePredicate none();
      static ScopePredicate eq(String field, Object value);
      static ScopePredicate in(String field, java.util.Collection<?> values);
      static ScopePredicate notNull(String field);
      static ScopePredicate and(ScopePredicate... parts);
      static ScopePredicate or(ScopePredicate... parts);
      static ScopePredicate not(ScopePredicate part);
  }
  public final class ScopePredicates {
      public static boolean matches(TemporalRow row, ScopePredicate predicate,
                                    ObjectSchema schema);
      public static ScopePredicate and(java.util.List<ScopePredicate> parts);
      public static ScopePredicate or(java.util.List<ScopePredicate> parts);
  }
  ```
- Consumes: `TemporalRow`、`ObjectSchema`、`FieldType`、`ContractException`。

- [ ] **Step 1: 写失败测试**

`ScopePredicateTest` 至少覆盖：
```java
@Test void eqMatchesCoercedValue() {
    var schema = ObjectSchema.builder("employee", "hr_employee")
            .required("employee_no", FieldType.TEXT)
            .field("hire_date", FieldType.DATE).build();
    var row = new TemporalRow("employee", version(), Map.of(
            "employee_no", "E-1", "hire_date", LocalDate.of(2026, 1, 5)));
    assertThat(ScopePredicates.matches(row,
            ScopePredicate.eq("hire_date", "2026-01-05"), schema)).isTrue();
}
@Test void notMatchesNullField() {
    // row 的 org_unit_id 为 null；Not(Eq(org_unit_id, "D01")) 必须为 true
}
@Test void inRejectsMoreThanOneThousandValues() {
    assertThatThrownBy(() -> ScopePredicate.in("employee_no",
            IntStream.range(0, 1001).mapToObj(i -> "E-" + i).toList()))
            .isInstanceOf(IllegalArgumentException.class);
}
@Test void unknownFieldFailsFast() { /* matches 抛 IllegalArgumentException */ }
@Test void compositionFlattensAndShortCircuits() { /* All/None 单位元与吸收元 */ }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -o -pl core -Dtest=ScopePredicateTest test`
Expected: FAIL，`ScopePredicate` 不存在。

- [ ] **Step 3: 实现**

- `Eq` 的 `value` 为 null → `None`；`In` 空集合 → `None`；`In` 超过 1000 → `IllegalArgumentException`；null 元素丢弃，全为 null → `None`。
- `NotNull` 校验字段名后保留。
- `matches` 用 `schema.requireField(field)` 取类型并 `coerce`；未知字段抛 `IllegalArgumentException`。
- `Not` 是二值逻辑：`matches(not(p)) == !matches(p)`。
- `and/or` 扁平化、去重（用 `LinkedHashSet` 保持顺序），`All/None` 按布尔代数化简。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -o -pl core -Dtest=ScopePredicateTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/domain/ScopePredicate.java \
        core/src/main/java/cn/byteforce/seedj/domain/ScopePredicates.java \
        core/src/test/java/cn/byteforce/seedj/domain/ScopePredicateTest.java
git commit -m "feat(domain): add scoped predicate AST and evaluator"
```

---

### Task 2: `TemporalStore` 带 scope 的读接口 + 内存适配器

**Files:**
- Modify: `core/src/main/java/cn/byteforce/seedj/domain/TemporalStore.java`
- Modify: `seedj-infra-local/src/main/java/cn/byteforce/seedj/infra/local/domain/InMemoryTemporalStore.java`
- Modify: `core/src/test/java/cn/byteforce/seedj/domain/TemporalStoreContractTest.java`
- Test: `seedj-infra-local/src/test/java/cn/byteforce/seedj/infra/local/domain/InMemoryTemporalStoreContractTest.java`（继承新契约）

**Interfaces:**
- Consumes: Task 1。
- Produces:
  ```java
  Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query,
                             ScopePredicate scope);
  Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope);
  List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope);
  default Optional<TemporalRow> current(String objectType, UUID id) {
      return find(objectType, id, TemporalQuery.current(), ScopePredicate.all());
  }
  ```

- [ ] **Step 1: 在契约测试里加失败用例**

在 `TemporalStoreContractTest` 增加：
```java
@Test void findReturnsEmptyWhenRowIsOutOfScope() { ... }
@Test void searchAppliesScopeBeforePaging() {
    // 3 行，scope 只允许 1 行；page size=1 时 items=1、total=1
}
@Test void searchTotalReflectsScope() { ... }
@Test void historyAppliesScope() { ... }
@Test void notNullScopeMatchesRowsWithValue() { ... }
```
所有实现子类自动继承。

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl seedj-infra-local -am -Dtest=InMemoryTemporalStoreContractTest test`
Expected: FAIL，接口方法签名不匹配。

- [ ] **Step 3: 实现**

- 修改 `TemporalStore` 三个方法；`current` 默认委托 `All`。
- `InMemoryTemporalStore` 在分页前用 `ScopePredicates.matches(row, scope, schema)` 过滤；`total` 用过滤后的集合大小。
- `history` 过滤。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl seedj-infra-local -am -Dtest=InMemoryTemporalStoreContractTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/domain/TemporalStore.java \
        seedj-infra-local/src/main/java/cn/byteforce/seedj/infra/local/domain/InMemoryTemporalStore.java \
        core/src/test/java/cn/byteforce/seedj/domain/TemporalStoreContractTest.java
git commit -m "feat(domain): require scope on temporal reads"
```

---

### Task 3: PostgreSQL 谓词下推

**Files:**
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/domain/ScopeSql.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/domain/PostgresTemporalStore.java`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/infra/jdbc/domain/ScopeSqlTest.java`

**Interfaces:**
- Consumes: Task 1、`TemporalTable`。
- Produces:
  ```java
  final class ScopeSql {
      record Clause(String sql, List<Object> args) {}
      static Clause compile(ScopePredicate predicate, TemporalTable table);
  }
  ```
- `PostgresTemporalStore` 的 `find/search/history` 把 `scope` 编译后以 `AND (<scope sql>)` 拼进 `WHERE`，参数追加在业务参数之后。

- [ ] **Step 1: 写失败测试**

`ScopeSqlTest` 覆盖：
```java
@Test void eqUsesParameterizedValue() {
    var clause = ScopeSql.compile(
            ScopePredicate.eq("org_unit_id", "D01"), table());
    assertThat(clause.sql()).isEqualTo("org_unit_id = ?");
    assertThat(clause.args()).containsExactly("D01");
}
@Test void inUsesPlaceholdersAndCoerces() { ... }
@Test void notUsesIsNotTrue() {
    assertThat(ScopeSql.compile(ScopePredicate.not(
            ScopePredicate.eq("org_unit_id", "D01")), table()).sql())
            .isEqualTo("(org_unit_id = ?) IS NOT TRUE");
}
@Test void allAndNoneBecomeConstants() { ... }
@Test void unknownFieldIsRejected() { ... }
@Test void notNullBecomesIsNotNull() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl seedj-jdbc-postgresql -am -Dtest=ScopeSqlTest test`
Expected: FAIL，`ScopeSql` 不存在。

- [ ] **Step 3: 实现**

- 字段白名单用 `table.columns().keySet()`，不是 `filterableColumns()`；scope 允许非 filterable 字段。
- `Eq/In/NotNull` 用 `table.fieldTypes().get(field).coerce(value)` 转换。
- `In` 为空时 `FALSE`；实际实现前 `ScopePredicate` 已把空 `In` 归一为 `None`。
- `All → TRUE`、`None → FALSE`、`Not → (<sql>) IS NOT TRUE`。
- `And/Or` 用括号包裹，避免优先级问题。

- [ ] **Step 4: 运行单元测试 + 契约测试**

Run: `./mvnw -o -pl seedj-jdbc-postgresql -am -Dtest=ScopeSqlTest test`
Expected: PASS。
Run（需要 Docker/PostgreSQL）: `./mvnw -o -pl seedj-jdbc-postgresql -am -Dtest=PostgresTemporalStoreContractTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/domain/ScopeSql.java \
        seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/domain/PostgresTemporalStore.java \
        seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/infra/jdbc/domain/ScopeSqlTest.java
git commit -m "feat(jdbc): compile scope predicates into parameterized SQL"
```

---

### Task 4: 字段隐私层级

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/domain/Privacy.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/Masker.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/Maskers.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/domain/FieldDefinition.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/domain/ObjectSchema.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeSchema.java`（编译修复，Task 10 再删除）
- Test: `core/src/test/java/cn/byteforce/seedj/domain/FieldDefinitionTest.java`
- Test: `core/src/test/java/cn/byteforce/seedj/domain/ObjectSchemaTest.java`

**Interfaces:**
- Produces:
  ```java
  public enum Privacy { PUBLIC, INTERNAL, SENSITIVE, RESTRICTED }
  @FunctionalInterface public interface Masker { Object mask(String field, Object value); }
  public record FieldDefinition(String name, FieldType type, boolean required,
                                boolean writeOnly, boolean eventVisible, boolean filterable,
                                boolean sortable, boolean indexed, Privacy privacy,
                                Masker masker) {
      static FieldDefinition of(String name, FieldType type);
      static FieldDefinition required(String name, FieldType type);
      static FieldDefinition writeOnly(String name, FieldType type);
      static FieldDefinition filterable(String name, FieldType type);
      FieldDefinition withRequired();
      FieldDefinition withFilterable();
      FieldDefinition withSortable();
      FieldDefinition withIndexed();
      FieldDefinition withPrivacy(Privacy privacy);
      FieldDefinition withMasker(Masker masker);
  }
  ```
- `Maskers.last4()`、`Maskers.email()`、`Maskers.fixed(String)`。
- `ObjectSchema` 构造期校验：
  - `writeOnly && eventVisible` → `IllegalArgumentException`（已有）。
  - `privacy != PUBLIC && eventVisible` → `IllegalArgumentException`。

- [ ] **Step 1: 写失败测试**

```java
@Test void defaultsArePublicAndNotIndexed() { ... }
@Test void sensitiveFieldCannotBeEventVisible() {
    assertThatThrownBy(() -> ObjectSchema.builder("employee", "hr_employee")
            .field(new FieldDefinition("id_number", FieldType.TEXT, false, false, true,
                    false, false, false, Privacy.SENSITIVE, null))
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventVisible");
}
@Test void maskersLastFourKeepsSuffix() {
    assertThat(Maskers.last4().mask("id_number", "110101199001011234"))
            .isEqualTo("****1234");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl core -Dtest=FieldDefinitionTest,ObjectSchemaTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `FieldDefinition` 扩展为 10 个组件；现有 `of/required/writeOnly/filterable` 保持默认值 `sortable=false, indexed=false, privacy=PUBLIC, masker=null`。
- 用编译器错误迁移所有 `new FieldDefinition(...)` 调用点（core、seedj-jdbc-postgresql、seedj-server/hr、seedj-it）。
- `Maskers.last4`：值转字符串，长度 ≤ 4 时返回 `"****"`，否则 `"****" + 后四位`。
- `Maskers.email`：保留 `@` 前后首字符，其余 `*`。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl core -Dtest=FieldDefinitionTest,ObjectSchemaTest test`
Expected: PASS。
Run: `./mvnw -o -pl seedj-infra-local,seedj-jdbc-postgresql -am -DskipTests compile`
Expected: BUILD SUCCESS（确认所有调用点已迁移）。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/domain/Privacy.java \
        core/src/main/java/cn/byteforce/seedj/domain/Masker.java \
        core/src/main/java/cn/byteforce/seedj/domain/Maskers.java \
        core/src/main/java/cn/byteforce/seedj/domain/FieldDefinition.java \
        core/src/main/java/cn/byteforce/seedj/domain/ObjectSchema.java \
        seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeSchema.java \
        core/src/test/java/cn/byteforce/seedj/domain/FieldDefinitionTest.java \
        core/src/test/java/cn/byteforce/seedj/domain/ObjectSchemaTest.java
git commit -m "feat(domain): add field privacy levels and maskers"
```

---

### Task 5: `ScopeContext` 与请求作用域

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/ScopeKey.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/ScopeKeyType.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/ScopeKeyCatalog.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/ScopeContext.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/ScopeContextHolder.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/ContextSink.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/ContextWriter.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/ContextResolver.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/DefaultContextResolver.java`
- Test: `core/src/test/java/cn/byteforce/seedj/core/security/ScopeContextTest.java`

**Interfaces:**
- Produces:
  ```java
  public record ScopeKey<T>(String name, ScopeKeyType type) {}
  public enum ScopeKeyType { STRING, NUMBER, BOOLEAN, DATE, LIST_STRING, LIST_UUID }
  public final class ScopeKeyCatalog {
      static ScopeKeyCatalog of(Collection<ScopeKey<?>> keys);
      ScopeKey<?> require(String name);
      void require(ScopeKey<?> key);
      Map<String, ScopeKeyType> keys();
  }
  public final class ScopeContext {
      static ScopeContext empty();
      static ScopeContext of(Map<String, Object> facts);
      Optional<Object> get(String key);
      <T> Optional<T> get(ScopeKey<T> key);
      Map<String, Object> facts();
  }
  @FunctionalInterface public interface ContextSink {
      <T> void put(ScopeKey<T> key, T value);
  }
  public interface ContextWriter {
      default int order() { return 0; }
      void write(ContextSink sink, Caller caller);
  }
  public interface ContextResolver {
      ScopeContext resolve(Caller caller);
  }
  public final class DefaultContextResolver implements ContextResolver {
      DefaultContextResolver(List<ContextWriter> writers);
  }
  public final class ScopeContextHolder {
      static Optional<ScopeContext> current();
      static Scope open(ScopeContext context); // extends AutoCloseable
  }
  ```
- `ScopeContextHolder` 嵌套作用域恢复进入前的值；`close()` 幂等。

- [ ] **Step 1: 写失败测试**

```java
@Test void duplicateKeyFails() {
    var resolver = new DefaultContextResolver(List.of(
            (sink, caller) -> sink.put(ORG_UNITS, List.of("D01")),
            (sink, caller) -> sink.put(ORG_UNITS, List.of("D02"))));
    assertThatThrownBy(() -> resolver.resolve(caller()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("org.units");
}
@Test void writersRunInOrder() { /* 两个 writer 拼接 list，断言顺序 */ }
@Test void holderRestoresPreviousOnClose() { ... }
@Test void typedGetRejectsWrongType() { ... }
@Test void unknownKeyIsRejected() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl core -Dtest=ScopeContextTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `ScopeContext.of` 复制为不可变 map；null value 拒绝。
- `get(ScopeKey<T>)` 按 `type` 校验；不匹配抛 `IllegalArgumentException`。
- `DefaultContextResolver` 按 `order()` 升序执行 writers；`ContextSink` 实现拒绝重复 key；writer 抛异常原样上抛。
- `ScopeContextHolder` 用 `ThreadLocal`，`open` 返回的 `Scope` 在 `close` 时恢复。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl core -Dtest=ScopeContextTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/core/security/ScopeKey.java \
        core/src/main/java/cn/byteforce/seedj/core/security/ScopeKeyType.java \
        core/src/main/java/cn/byteforce/seedj/core/security/ScopeContext.java \
        core/src/main/java/cn/byteforce/seedj/core/security/ScopeContextHolder.java \
        core/src/main/java/cn/byteforce/seedj/core/security/ContextSink.java \
        core/src/main/java/cn/byteforce/seedj/core/security/ContextWriter.java \
        core/src/main/java/cn/byteforce/seedj/core/security/ContextResolver.java \
        core/src/main/java/cn/byteforce/seedj/core/security/DefaultContextResolver.java \
        core/src/test/java/cn/byteforce/seedj/core/security/ScopeContextTest.java
git commit -m "feat(security): add frozen request scope context"
```

---

### Task 6: `ObjectModules` 读侧 DSL 与注册表

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/read/ReadModel.java`
- Create: `core/src/main/java/cn/byteforce/seedj/read/ReadModelRegistry.java`
- Create: `core/src/main/java/cn/byteforce/seedj/module/ObjectModuleConfigurer.java`
- Create: `core/src/main/java/cn/byteforce/seedj/module/ObjectModules.java`
- Create: `core/src/main/java/cn/byteforce/seedj/module/ObjectModuleRegistry.java`
- Test: `core/src/test/java/cn/byteforce/seedj/module/ObjectModulesTest.java`

**Interfaces:**
- Produces:
  ```java
  public record ReadModel(String objectType, ObjectSchema schema, String readPermission) {}
  public interface ReadModelRegistry {
      Optional<ReadModel> find(String objectType);
      List<ReadModel> all();
  }
  @FunctionalInterface public interface ObjectModuleConfigurer {
      void configure(ObjectModules modules);
  }
  public final class ObjectModules {
      static ObjectModules create();
      ObjectSpec object(String objectType, String tableName);
      ObjectModuleRegistry freeze();
  }
  public final class ObjectModuleRegistry implements ReadModelRegistry {
      Optional<ReadModel> find(String objectType);
      List<ReadModel> all();
      List<QueryDeclaration> queries();
      ScopeKeyCatalog scopeKeys();
  }
  ```
- `ObjectSpec`（嵌套静态类）：
  `readPermission(String)`、`contextKey(ScopeKey<?>)`、`field(String, FieldType)`（返回 `FieldSpec`）、`query(Class<?>)`（返回 `QuerySpec`）。
- `FieldSpec`：`required()/writeOnly()/filterable()/sortable()/indexed()/privacy(Privacy)/mask(Masker)`。
- `QuerySpec`：`path(String)`、`permission(String)`、`sort(String)`。
- `QueryDeclaration` 记录：`objectType, Class<?> viewType, String path, String permission, String sortField`。

- [ ] **Step 1: 写失败测试**

```java
@Test void freezeBuildsSchemaAndReadModel() {
    var modules = ObjectModules.create();
    modules.object("employee", "hr_employee")
            .readPermission("hr.employee.read")
            .field("employee_no", FieldType.TEXT).required().filterable().sortable()
            .field("id_number", FieldType.TEXT).privacy(Privacy.SENSITIVE)
            .query(Map.class).path("/api/employees").permission("hr.employee.read")
            .sort("employee_no");
    var registry = modules.freeze();
    assertThat(registry.find("employee").orElseThrow().readPermission())
            .isEqualTo("hr.employee.read");
    assertThat(registry.queries()).hasSize(1);
}
@Test void missingReadPermissionFailsAtFreeze() { ... }
@Test void duplicateObjectTypeFailsAtFreeze() { ... }
@Test void unknownQuerySortFieldFailsAtFreeze() { ... }
@Test void queryPermissionDefaultsToReadPermission() { ... }
@Test void duplicateContextKeyWithDifferentTypeFails() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl core -Dtest=ObjectModulesTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- DSL 内部保存 `ObjectSpec` 列表；`freeze()` 一次性构建 `ObjectSchema`、`ReadModel`、`QueryDeclaration`、`ScopeKeyCatalog`。
- 校验：object/table 标识符；readPermission 非空；query path 以 `/` 开头且唯一；sort 字段存在且 `sortable`；context key 同名同类型；至少一个字段。
- `QuerySpec.permission` 省略时继承 `ReadModel.readPermission`。
- `ScopeKeyCatalog` 是 `Map<String, ScopeKeyType>` 的不可变包装，提供 `require(String)` / `require(ScopeKey<?>)`。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl core -Dtest=ObjectModulesTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/read/ReadModel.java \
        core/src/main/java/cn/byteforce/seedj/read/ReadModelRegistry.java \
        core/src/main/java/cn/byteforce/seedj/module/ObjectModuleConfigurer.java \
        core/src/main/java/cn/byteforce/seedj/module/ObjectModules.java \
        core/src/main/java/cn/byteforce/seedj/module/ObjectModuleRegistry.java \
        core/src/test/java/cn/byteforce/seedj/module/ObjectModulesTest.java
git commit -m "feat(module): add read-side object module DSL"
```

---

### Task 7: `ReadGateway`

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/read/DataScopeResolver.java`
- Create: `core/src/main/java/cn/byteforce/seedj/read/FieldAccess.java`
- Create: `core/src/main/java/cn/byteforce/seedj/read/FieldPolicy.java`
- Create: `core/src/main/java/cn/byteforce/seedj/read/FieldPolicyResolver.java`
- Create: `core/src/main/java/cn/byteforce/seedj/read/ReadAccessEvent.java`
- Create: `core/src/main/java/cn/byteforce/seedj/read/ReadAuditSink.java`
- Create: `core/src/main/java/cn/byteforce/seedj/read/ReadGateway.java`
- Test: `core/src/test/java/cn/byteforce/seedj/read/ReadGatewayTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface DataScopeResolver {
      boolean supports(String objectType);
      default int order() { return 0; }
      ScopePredicate resolve(String objectType, Caller caller, ScopeContext context);
  }
  public record FieldAccess(Action action, String policyId) {
      enum Action { FULL, MASKED, HIDDEN }
  }
  public record FieldPolicy(Map<String, FieldAccess> fields) {
      static FieldPolicy defaults(ObjectSchema schema);
      FieldPolicy narrow(FieldPolicy other);
  }
  public interface FieldPolicyResolver {
      boolean supports(String objectType);
      default int order() { return 0; }
      FieldPolicy resolve(String objectType, Caller caller, ScopeContext context);
  }
  public record ReadAccessEvent(UUID id, String callerId, String objectType,
                                Operation operation, Map<String, Object> filters,
                                List<UUID> rowIds, String scopeDigest,
                                Set<String> policyIds, Instant at) {
      enum Operation { FIND, SEARCH, HISTORY }
  }
  public interface ReadAuditSink {
      void record(ReadAccessEvent event);
      static ReadAuditSink noop() { return event -> {}; }
  }
  public final class ReadGateway {
      ReadGateway(ReadModelRegistry models, TemporalStore store,
                  List<DataScopeResolver> scopes, List<FieldPolicyResolver> fieldPolicies,
                  ReadAuditSink audit, Clock clock, IdGenerator<UUID> ids);
      Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query, Caller caller);
      Page<TemporalRow> search(String objectType, TemporalSearch search, Caller caller);
      List<TemporalRow> history(String objectType, UUID id, Caller caller);
  }
  ```
- 默认字段策略：`PUBLIC/INTERNAL → FULL`，`SENSITIVE/RESTRICTED → HIDDEN`，`writeOnly → HIDDEN`。
- 组合：scope `AND` 收窄；字段 `narrow` 取最窄动作。

- [ ] **Step 1: 写失败测试**

```java
@Test void missingResolverReturns403WithoutTouchingStore() {
    var store = mock(TemporalStore.class);
    var gateway = gateway(store, List.of());
    assertThatThrownBy(() -> gateway.search("employee", search(), caller()))
            .isInstanceOf(ContractException.class);
    verifyNoInteractions(store);
}
@Test void twoResolversAreAnded() { ... }
@Test void sensitiveFieldIsHiddenByDefault() { ... }
@Test void maskedFieldUsesMasker() { ... }
@Test void writeOnlyFieldIsAlwaysRemoved() { ... }
@Test void resolverFailureBecomes503() { ... }
@Test void auditRecordsScopeDigestAndPolicyIds() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl core -Dtest=ReadGatewayTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `requireReadable`：未知 objectType → 404；`caller == null` → 401；缺 `readPermission` → 403；caller 无权限 → 403。
- scope：过滤 `supports`，按 `order` 排序，逐个 `resolve`；任一返回 null/未知字段 → `IllegalStateException`（500）；无 resolver → 403。
- fields：`FieldPolicy.defaults(schema)` 逐个 `narrow`；`FieldPolicy` 里的未知字段 → 500。
- render：`HIDDEN` 删除；`MASKED` 有 masker 则打码，无则删除；`writeOnly` 先删除。
- audit：`scopeDigest = sha256(CanonicalJsonHelper.canonicalize(predicate))`；审计异常捕获并记录 ERROR，不阻断。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl core -Dtest=ReadGatewayTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/read \
        core/src/test/java/cn/byteforce/seedj/read/ReadGatewayTest.java
git commit -m "feat(read): add single read gateway with scope and field policy"
```

---

### Task 8: biz-starter 读装配与通用查询面

**Files:**
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/read/ScopeContextFilter.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/read/RequestContextDecorator.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/read/ObjectModuleAutoConfiguration.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/read/ReadAutoConfiguration.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/read/QueryEndpointAutoConfiguration.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/read/ViewMapper.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/domain/web/ObjectQueryController.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/BizSecurityAutoConfiguration.java`（用 `RequestContextDecorator` 替换 `callerTaskDecorator`）
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/wiring/TemporalCommandAutoConfiguration.java`（移除 `ObjectQueryController` bean）
- Modify: `seedj-biz-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/starter/read/ReadAutoConfigurationTest.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/starter/domain/web/ObjectQueryControllerTest.java`

**Interfaces:**
- Consumes: Task 5、6、7。
- Produces:
  - `ObjectModuleAutoConfiguration#objectModuleRegistry(List<ObjectModuleConfigurer>)`
  - `ReadAutoConfiguration#readGateway(...)`
  - `ScopeContextFilter`（`OncePerRequestFilter`，认证后打开作用域，`finally` 关闭）
  - `RequestContextDecorator`（`TaskDecorator`，同时搬 `Caller` 与 `ScopeContext`）
  - `QueryEndpointAutoConfiguration#moduleQueryRoutes(...)`（`RouterFunction<ServerResponse>`）
  - `ViewMapper#map(TemporalRow row, Class<?> viewType)`
- 通用 `/objects/**` 只在 `seedj.read.generic-surface=enabled` 时注册；否则端点不存在（404）。

- [ ] **Step 1: 写失败测试**

```java
@Test void registryIsBuiltFromConfigurers() { /* ApplicationContextRunner */ }
@Test void readGatewayIsPresent() { ... }
@Test void genericSurfaceIsAbsentByDefault() { ... }
@Test void genericSurfaceRequiresExplicitProperty() { ... }
@Test void queryRouteMapsSnakeCaseToCamelCase() { ... }
@Test void asyncDecorationCarriesCallerAndScopeContext() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl seedj-biz-starter -am -Dtest=ReadAutoConfigurationTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `ObjectModuleAutoConfiguration`：收集 configurer，`ObjectModules.create()` → 逐个 `configure` → `freeze()`。
- `ReadAutoConfiguration`：`ReadGateway`、`ReadAuditSink`（默认 `noop`，可覆盖）、`ScopeContextFilter`、`RequestContextDecorator`、`ContextResolver`（`DefaultContextResolver` + registry 的 `ScopeKeyCatalog` + 所有 `ContextWriter`）。
- `ViewMapper`：缓存 `Class` → `RecordComponent[]`；`employee_no` → `employeeNo`；
  meta 组件 `id`/`version`/`validFrom`/`validTo` 从 `row.version()` 取；类型转换复用
  `FieldType`；找不到字段给 null（primitive 组件直接报错，不静默给 0）。
- `QueryEndpointAutoConfiguration`：对 `registry.queries()` 每个 query 建 `GET` route；参数 `page`/`size`；`as_of`/`recorded_as_of` 透传；调用 gateway；`viewMapper.map`。
- 删除 `ObjectQueryController` 的 1 参构造器；改为 `(ReadGateway, CallerProvider)`；`/objects` 的 `find/list` 走 gateway，不再直接拿 store。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl seedj-biz-starter -am -Dtest=ReadAutoConfigurationTest,ObjectQueryControllerTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/read \
        seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/domain/web/ObjectQueryController.java \
        seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/BizSecurityAutoConfiguration.java \
        seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/wiring/TemporalCommandAutoConfiguration.java \
        seedj-biz-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
        seedj-biz-starter/src/test/java/cn/byteforce/seedj/starter/read \
        seedj-biz-starter/src/test/java/cn/byteforce/seedj/starter/domain/web/ObjectQueryControllerTest.java
git commit -m "feat(starter): wire read gateway, scope filter and module routes"
```

---

### Task 9: IAM 策略 → 谓词 / 字段策略

**Files:**
- Create: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/starter/iam/read/PolicyScopeCompiler.java`
- Create: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/starter/iam/read/IamDataScopeResolver.java`
- Create: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/starter/iam/read/IamFieldPolicyResolver.java`
- Create: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/starter/iam/read/ReadPolicyAutoConfiguration.java`
- Test: `seedj-iam-starter/src/test/java/cn/byteforce/seedj/starter/iam/read/PolicyScopeCompilerTest.java`
- Test: `seedj-iam-starter/src/test/java/cn/byteforce/seedj/starter/iam/read/IamFieldPolicyResolverTest.java`

**Interfaces:**
- Consumes: Task 1、5、6、7；`IamPolicyRepository`、`IamPolicyDecisionPoint`。
- Produces:
  ```java
  public final class PolicyScopeCompiler {
      PolicyScopeCompiler(IamPolicyRepository policies, ObjectModuleRegistry modules,
                          ScopeKeyCatalog keys);
      ScopePredicate compile(String objectType, Caller caller, ScopeContext context);
  }
  public final class IamDataScopeResolver implements DataScopeResolver { ... }
  public final class IamFieldPolicyResolver implements FieldPolicyResolver { ... }
  ```
- 编译规则见 spec §3.4：`subject.*`/`environment.*` 折叠；`resource.field` 保留；字段对字段拒绝；ALLOW OR；DENY `Not`；`Exists(resource.field)` → `NotNull`；`NEQ/NOT_IN` → `Not`。

- [ ] **Step 1: 写失败测试**

```java
@Test void compilesSubjectToConstant() {
    // ALLOW read employee WHERE subject.department == resource.org_unit_id
    // caller.attributes().get("department") = "D01"
    // → Eq("org_unit_id", "D01")
}
@Test void compilesDenyAsNot() { ... }
@Test void unsupportedFieldToFieldComparisonFails() { ... }
@Test void unknownEnvironmentKeyFails() { ... }
@Test void multipleAllowsBecomeOr() { ... }
@Test void noAllowBecomesNone() { ... }
@Test void fieldResolverSkipsFieldsWithoutMatchingPolicy() { ... }
@Test void fieldResolverAppliesExplicitDeny() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl seedj-iam-starter -am -Dtest=PolicyScopeCompilerTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- 用 `IamPolicy` 的 `actions/resources/priority/effect/condition`；只处理 action `read`（行）与 `read`/`read.masked`（字段）。
- `PolicyCondition.Compare` 的操作数用 `PolicyOperand.source()` 区分 `SUBJECT/ENVIRONMENT/RESOURCE`。
- 常量比较在编译期求值；无法求值 → `IllegalArgumentException` 带 policy id。
- 字段解析器：对每个非 `writeOnly` 字段调 PDP 两次；`deniedBy == null` 表示没有匹配策略 → 跳过（保留 schema 默认）；显式 DENY → `HIDDEN`。
- `ReadPolicyAutoConfiguration` 条件：存在 `IamPolicyRepository` 与 `IamPolicyDecisionPoint`；注册两个 resolver。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl seedj-iam-starter -am -Dtest=PolicyScopeCompilerTest,IamFieldPolicyResolverTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-iam-starter/src/main/java/cn/byteforce/seedj/starter/iam/read \
        seedj-iam-starter/src/test/java/cn/byteforce/seedj/starter/iam/read
git commit -m "feat(iam): compile policies into scope predicates and field access"
```

---

### Task 10: HR 读面迁移与数据范围 e2e

**Files:**
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeModule.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrScopeKeys.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrOrgUnitScopeResolver.java`
- Delete: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeQueryController.java`
- Delete: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeQueryService.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeView.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeConfiguration.java`（删除 schema/query/agent 装配，保留派生字段/脱敏/ID）
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeSchema.java`（删除，字段移入 module）
- Test: `seedj-server/hr/src/test/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeReadE2eTest.java`

**Interfaces:**
- Consumes: Task 6-9。
- Produces:
  - `HrEmployeeModule implements ObjectModuleConfigurer`：`employee` 字段、`readPermission("hr.employee.read")`、`query(HrEmployeeView.class).path("/api/hr/employees").sort("employee_no")`、`contextKey(HrScopeKeys.ORG_UNITS)`。
  - `HrScopeKeys.ORG_UNITS = new ScopeKey<>("org.units", LIST_STRING)`。
  - `HrOrgUnitScopeResolver implements DataScopeResolver`：从 `ScopeContext` 取 `org.units`，返回 `In("org_unit_id", units)`；缺失 → `None`（不返回 403，因为 resolver 存在但无单位）。

- [ ] **Step 1: 写失败测试**

```java
@Test void managerSeesSubtree() { /* org.units=[D01,D02] → 2 rows */ }
@Test void employeeSeesOwnUnitOnly() { /* org.units=[D01] → 1 row */ }
@Test void noOrgUnitsYieldsEmpty() { /* org.units=[] → items empty, total 0 */ }
@Test void phoneIsMasked() { /* 响应里 phone 为 ****1234 */ }
@Test void writeOnlyHashIsAbsent() { /* id_number_hash 不在响应 */ }
@Test void pageTotalReflectsScope() { /* size=1, total=2 */ }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl seedj-server/hr -am -Dtest=HrEmployeeReadE2eTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `HrEmployeeModule` 是 `@Configuration(proxyBeanMethods = false)`，实现 `ObjectModuleConfigurer`；`HrEmployeeView` 增加 `phone` 组件。字段：`employee_no`（required/filterable/sortable/indexed）、`name`（required）、`status`（required/filterable）、`hire_date`、`employment_type`、`work_location`、`phone`（SENSITIVE + `Maskers.last4`）、`id_number_hash`（writeOnly）、`org_unit_id`（filterable/indexed）。命令专用的 `id_number` 在 Task 14 用 `.commandField("id_number")` 豁免。
- 测试配置提供 `ContextWriter`，从 `Caller.attributes().get("org_units")` 写 `HrScopeKeys.ORG_UNITS`；生产由 HR 自己实现。
- 删除两个读类；`HrEmployeeView` 保持 camelCase 组件名（`employeeNo` 等），由 `ViewMapper` 映射。
- `HrEmployeeConfiguration` 删除 `hrEmployeeSchema`、`hrEmployeeQueryService`、`@Bean CommandDescriptor`（Task 14 删 agent 时再删 handler 相关）。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl seedj-server/hr -am -Dtest=HrEmployeeReadE2eTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee \
        seedj-server/hr/src/test/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeReadE2eTest.java
git commit -m "feat(hr): migrate employee reads to scoped gateway"
```

---

## Milestone 2：写路径开发体验

### Task 11: `CommandHandler` / `CommandContext` / 模板基类

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandHandler.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandContext.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/AbstractCommandHandler.java`
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/CommandContextTest.java`
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/AbstractCommandHandlerTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface CommandHandler<C> {
      UUID targetId(C command);
      AppendRequest decide(C command, CommandContext ctx);
      default List<CommandEvent> events(C command, AppendResult result, CommandContext ctx) {
          return List.of();
      }
  }
  public final class CommandContext {
      CommandContext(UUID commandId, UUID targetId, Optional<TemporalRow> current,
                     Instant now, UUID actor, Caller caller, ScopePredicate writeScope,
                     ObjectSchema schema, IdGenerator<UUID> ids);
      UUID commandId(); UUID targetId(); Optional<TemporalRow> current(); Instant now();
      UUID actor(); Caller caller(); ScopePredicate writeScope(); ObjectSchema schema();
      CommandEvent event(String type, Map<String, Object> payload);
  }
  public abstract class AbstractCommandHandler<C> implements CommandHandler<C> {
      protected final TemporalRow requireCurrent(CommandContext ctx);
      protected final Map<String, Object> merge(CommandContext ctx, Map<String, Object> changes);
      protected final AppendRequest create(CommandContext ctx, Instant validFrom,
                                           Map<String, Object> fields);
      protected final AppendRequest correct(CommandContext ctx, Map<String, Object> fields);
      protected final ContractException reject(ContractErrorCode code, String reason);
  }
  ```
- `CommandContext.event`：`idempotency_key = commandId + ":" + type + ":" + index`，`index` 从 0 递增，事件 id 用 `ids.id()`。
- `merge`：只合并 value 非 null 的字段；未知字段抛 `IllegalArgumentException`。

- [ ] **Step 1: 写失败测试**

```java
@Test void eventKeysAreCommandScopedAndIncrementing() {
    var ctx = context();
    var first = ctx.event("hr.employee.created", Map.of());
    var second = ctx.event("hr.employee.created", Map.of());
    assertThat(first.idempotencyKey()).isEqualTo(ctx.commandId() + ":hr.employee.created:0");
    assertThat(second.idempotencyKey()).isEqualTo(ctx.commandId() + ":hr.employee.created:1");
}
@Test void mergeIgnoresNullsAndRejectsUnknownFields() { ... }
@Test void requireCurrentFailsWithObjectNotFound() { ... }
@Test void createAndCorrectUseContextTarget() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl core -Dtest=CommandContextTest,AbstractCommandHandlerTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `CommandContext` 是 final class（不是 record），持 `AtomicInteger` 事件序号。
- `AbstractCommandHandler` 无状态；schema 从 `ctx` 取。
- `create` 用 `AppendRequest.create(schema.objectType(), ctx.targetId(), validFrom, null, ctx.actor(), fields)`；`correct` 用 `AppendRequest.correction(...)`。
- `reject` 返回 `new ContractException(code, reason)`，由 handler 抛出。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl core -Dtest=CommandContextTest,AbstractCommandHandlerTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/cqrs/CommandHandler.java \
        core/src/main/java/cn/byteforce/seedj/cqrs/CommandContext.java \
        core/src/main/java/cn/byteforce/seedj/cqrs/AbstractCommandHandler.java \
        core/src/test/java/cn/byteforce/seedj/cqrs/CommandContextTest.java \
        core/src/test/java/cn/byteforce/seedj/cqrs/AbstractCommandHandlerTest.java
git commit -m "feat(cqrs): add explicit command handler seam"
```

---

### Task 12: `CommandRuntime` 改为执行 handler

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandRegistration.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/WriteScopeProvider.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandRuntime.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandCatalog.java`
- Delete: `core/src/main/java/cn/byteforce/seedj/cqrs/TemporalCommandAgent.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandDescriptor.java`（仅保留字段，不再承担执行）
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/CommandRuntimeTest.java`
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/CommandCatalogTest.java`

**Interfaces:**
- Produces:
  ```java
  public record CommandRegistration(CommandDescriptor descriptor, CommandHandler<?> handler) {}
  @FunctionalInterface public interface WriteScopeProvider {
      ScopePredicate scope(String objectType, Caller caller);
  }
  public final class CommandRuntime {
      CommandReceipt execute(CommandSubmission submission, CommandRegistration registration,
                             Caller caller, ScopePredicate writeScope);
      // Options 增加 JsonMapper（pendingLease 在 Task 16 加）
  }
  public final class CommandCatalog {
      CommandCatalog(List<CommandRegistration> registrations, CommandPolicy policy,
                     CommandRuntime runtime, WriteScopeProvider writeScopes);
      CommandReceipt execute(CommandSubmission submission, Caller caller);
  }
  ```
- 执行顺序：幂等回放（`(issuer, key)`）→ 解码 → 校验 → `targetId` → PENDING → 锁 → 事务 → `current` → 写范围检查 → `decide` → 写范围检查（结果字段）→ append → events → outbox → 终态。
- `targetId` 在幂等命中时不调用。
- 事件 key 去重；重复 → `IllegalStateException`。

- [ ] **Step 1: 写失败测试**

```java
@Test void replayDoesNotCallTargetId() {
    var handler = new CountingHandler();
    runtime.execute(submission, registration(handler), caller(), ScopePredicate.all());
    runtime.execute(submission, registration(handler), caller(), ScopePredicate.all());
    assertThat(handler.targetIdCalls).isEqualTo(1);
}
@Test void writeScopeRejectsTargetBeforeDecide() { ... }
@Test void writeScopeRejectsCreatedFieldsOutsideScope() { ... }
@Test void duplicateEventKeysFail() { ... }
@Test void jsonMapperIsInjected() { /* 自定义命名策略生效 */ }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl core -Dtest=CommandRuntimeTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `CommandRuntime` 去掉 `Work` 接口；用 `CommandRegistration`。
- `Options` 增加 `JsonMapper jsonMapper`（默认 `JsonMapper.builder().build()`）。
- 幂等查询放在解码前；命中直接 `replayOrConflict`。
- `requireTargetId` 只在未命中时调用。
- 事务内：`ScopePredicates.matches(current, writeScope, schema)`；`decide` 后对 `AppendRequest.fields()` 构造 `TemporalRow` 再检查一次。
- 事件：先检查 key 唯一，再写 outbox。
- `CommandCatalog` 用 `WriteScopeProvider` 计算 scope；`CommandPolicy` 不变。
- 删除 `TemporalCommandAgent`；`CommandDescriptor` 保留 `type/objectType/payloadType/permission/validator/redactor`。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl core -Dtest=CommandRuntimeTest,CommandCatalogTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/cqrs \
        core/src/test/java/cn/byteforce/seedj/cqrs/CommandRuntimeTest.java \
        core/src/test/java/cn/byteforce/seedj/cqrs/CommandCatalogTest.java
git rm core/src/main/java/cn/byteforce/seedj/cqrs/TemporalCommandAgent.java
git commit -m "refactor(cqrs): execute explicit handlers with write scope"
```

---

### Task 13: `ObjectModules` 命令 DSL 与启动校验

**Files:**
- Modify: `core/src/main/java/cn/byteforce/seedj/module/ObjectModules.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/module/ObjectModuleRegistry.java`
- Create: `core/src/main/java/cn/byteforce/seedj/module/CommandDeclaration.java`（内部 record）
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/read/ObjectModuleAutoConfiguration.java`（构建 `CommandCatalog`）
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/wiring/TemporalCommandAutoConfiguration.java`（删除旧 `CommandCatalog` bean）
- Test: `core/src/test/java/cn/byteforce/seedj/module/ObjectModulesCommandTest.java`

**Interfaces:**
- Consumes: Task 6、11、12。
- Produces:
  ```java
  // ObjectModules.ObjectSpec
  CommandSpec command(String type, Class<?> payloadType);
  // CommandSpec
  CommandSpec permission(String permission);
  CommandSpec handler(CommandHandler<?> handler);
  CommandSpec creates();
  CommandSpec emits(String... eventTypes);
  CommandSpec commandField(String field);
  // ObjectModuleRegistry
  List<CommandRegistration> commands();
  ```
- 启动校验：
  - 命令类型唯一；
  - `permission` 非空；
  - `handler` 非空；
  - payload record 组件 ⊆ schema 字段 ∪ `commandField`；
  - `.creates()` 的命令 `targetId` 由运行时检查非空（无需启动期反射）。

- [ ] **Step 1: 写失败测试**

```java
@Test void commandDeclarationBuildsRegistration() { ... }
@Test void duplicateCommandTypeFailsAtFreeze() { ... }
@Test void missingPermissionFailsAtFreeze() { ... }
@Test void payloadFieldOutsideSchemaFailsAtFreeze() { ... }
@Test void commandFieldExemptsPayloadField() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl core -Dtest=ObjectModulesCommandTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `CommandDeclaration` 保存 `type/payloadType/permission/handler/creates/emits/extraFields`。
- `freeze()` 构建 `CommandRegistration`：descriptor 的 validator/redactor 默认 `CommandValidator.none()` / `CommandPayloadRedactor.none()`，可在 `CommandSpec` 覆盖。
- `ObjectModuleRegistry.commands()` 返回不可变列表。
- `ObjectModuleAutoConfiguration` 增加 `CommandCatalog` bean：`new CommandCatalog(registry.commands(), policy, runtime, writeScopes)`；`WriteScopeProvider` 由 `read` 的 resolver 列表组合实现（与 `ReadGateway` 共用 `ScopePredicate` 合成逻辑，抽一个 `ScopeResolvers` 工具）。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl core -Dtest=ObjectModulesCommandTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/module \
        seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/read/ObjectModuleAutoConfiguration.java \
        seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/wiring/TemporalCommandAutoConfiguration.java \
        core/src/test/java/cn/byteforce/seedj/module/ObjectModulesCommandTest.java
git commit -m "feat(module): declare commands with explicit handlers"
```

---

### Task 14: HR 写面迁移与状态机

**Files:**
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeCreateHandler.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeUpdateHandler.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/OrgUnitDirectory.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeCreate.java`（加 `String phone`）
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeUpdate.java`（加 `String phone`、`String status`）
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeModule.java`（加 commands）
- Delete: `HrEmployeeCreateAgent.java`、`HrEmployeeUpdateAgent.java`、`HrEmployeeFields.java`
- Modify: `HrEmployeeConfiguration.java`（只保留派生字段、脱敏、ID 生成、`OrgUnitDirectory`）
- Modify: `HrEmployeeSchemaCapability.java`（schema 从 registry 取）
- Test: `seedj-server/hr/src/test/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeHttpTest.java`
- Test: `seedj-server/hr/src/test/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeStateMachineTest.java`

**Interfaces:**
- Consumes: Task 11-13。
- Produces:
  - `HrEmployeeCreateHandler implements CommandHandler<HrEmployeeCreate>`：`targetId = ids.id()`；`decide` 要求 `current` 为空，生成 `employee_no`（`"EMP-" + ids.id()`），`status=pending_onboarding`，写入 `id_number_hash` 与 `phone`；`events` 用 `ctx.event`。
  - `HrEmployeeUpdateHandler implements CommandHandler<HrEmployeeUpdate>`：`targetId = command.employeeId()`；`requireCurrent`；状态机校验；`merge` 非空字段；`events`。
  - `OrgUnitDirectory`：`boolean exists(String orgUnitId)`；HR 测试提供 stub bean。
- 规则：
  - 创建：`status` 固定 `pending_onboarding`；`hire_date` 不得晚于今天。
  - 更新：`status` 只允许 `pending_onboarding -> active -> terminated`；`terminated`
    为终态，之后禁止改 `org_unit_id`、`employee_no`；`hire_date` 不得晚于今天；
    `org_unit_id` 必须存在。
  - 非法状态迁移/字段 → `ContractException(ContractErrorCode.INVALID_PAYLOAD, ...)`。

- [ ] **Step 1: 写失败测试**

```java
@Test void createStartsPendingOnboarding() { ... }
@Test void terminatedEmployeeCannotChangeOrgUnit() {
    // 先 active -> terminated；再改 org_unit_id
    // 期望 400，body.code = "EIS-400-002"，details.reason 含 terminated
}
@Test void invalidStatusTransitionIsRejected() { /* active -> pending_onboarding */ }
@Test void hireDateInFutureIsRejected() { ... }
@Test void unknownOrgUnitIsRejected() { ... }
@Test void createdEventKeyUsesCommandId() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl seedj-server/hr -am -Dtest=HrEmployeeHttpTest,HrEmployeeStateMachineTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- Handler 用 `AbstractCommandHandler`；字段 map 只放非 null；`id_number_hash` 由 `DerivedFieldRegistry` 计算。
- `HrEmployeeModule` 的 `.command(...)` 挂 handler 实例；configurer 构造器注入两个 handler。
- `HrEmployeeConfiguration` 删除 descriptor/agent bean；保留 `DerivedField`、`DerivedFieldRegistry`、`CommandPayloadRedactor`、`IdGenerator<String>`、`OrgUnitDirectory`。
- `HrEmployeeSchemaCapability` 注入 `ObjectModuleRegistry`，用 `registry.find("employee").schema()`。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl seedj-server/hr -am -Dtest=HrEmployeeHttpTest,HrEmployeeStateMachineTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee \
        seedj-server/hr/src/test/java/cn/byteforce/seedj/server/hr/employee
git commit -m "feat(hr): rewrite commands as explicit handlers with rules"
```

---

## Milestone 3：可靠性 P0 与收口

### Task 15: 幂等键作用域与命令归属

**Files:**
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/cqrs/CommandMigrations.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/spi/CommandStore.java`
- Modify: `seedj-infra-local/src/main/java/cn/byteforce/seedj/infra/local/cqrs/InMemoryCommandStore.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/cqrs/PostgresCommandStore.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandRuntime.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/web/CommandController.java`
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/CommandRuntimeTest.java`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/infra/jdbc/cqrs/PostgresCommandStoreContractTest.java`

**Interfaces:**
- Produces:
  ```java
  Optional<CommandRecord> findByIdempotencyKey(UUID issuer, String idempotencyKey);
  ```
- 唯一约束：`UNIQUE (issuer, idempotency_key)`。
- `GET /commands/{id}`：`record.issuer() == actor` 或 `caller.hasPermission("seedj.command.admin")`；否则 404。

- [ ] **Step 1: 写失败测试**

```java
@Test void sameKeyDifferentIssuerIsNotReplay() { ... }
@Test void replayOnlyReturnsOwnCommand() { ... }
@Test void statusOfAnotherIssuerIs404() { ... }
@Test void adminCanReadAnyCommand() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl core -Dtest=CommandRuntimeTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- 迁移 SQL 改 `CONSTRAINT uq_command_record_idempotency UNIQUE (issuer, idempotency_key)`。
- 库未发布：本任务直接改 V1，不写 V2；本地 PostgreSQL 重建 `command_record`
  （删除 `seedj_schema_history` 中该 scope 的记录或重建库）。
- 两个 store 的查询/插入按 issuer；in-memory key 用 `issuer + "\u0000" + key`。
- `CommandController.status` 取当前 caller，比较 actor；无权限返回 404。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl core -Dtest=CommandRuntimeTest test`
Run: `./mvnw -o -pl seedj-infra-local -am -Dtest=InMemoryCommandStoreContractTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/cqrs/spi/CommandStore.java \
        core/src/main/java/cn/byteforce/seedj/cqrs/CommandRuntime.java \
        seedj-infra-local/src/main/java/cn/byteforce/seedj/infra/local/cqrs/InMemoryCommandStore.java \
        seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/cqrs/CommandMigrations.java \
        seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/cqrs/PostgresCommandStore.java \
        seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/web/CommandController.java
git commit -m "fix(cqrs): scope idempotency by issuer and check command ownership"
```

---

### Task 16: PENDING lease 与回收

**Files:**
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandRecord.java`（新增 `leaseUntil`）
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/spi/CommandStore.java`
- Modify: `seedj-infra-local/src/main/java/cn/byteforce/seedj/infra/local/cqrs/InMemoryCommandStore.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/cqrs/CommandMigrations.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/cqrs/PostgresCommandStore.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandRuntime.java`（PENDING 写入 lease）
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/wiring/CommandReaper.java`
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/CommandRuntimeTest.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/starter/cqrs/wiring/CommandReaperTest.java`

**Interfaces:**
- Produces:
  ```java
  long expirePending(Instant now);
  ```
- `CommandRecord` 增加 `Instant leaseUntil`（PENDING 必填；终态保留原值）。
- `CommandRuntime.Options.pendingLease`（默认 60s）；PENDING 插入 `now.plus(pendingLease)`。
- `CommandReaper`：`@Scheduled(fixedDelayString = "${seedj.command.reaper-interval:30s}")`，启动时执行一次；指标 `seedj.command.pending.expired`。

- [ ] **Step 1: 写失败测试**

```java
@Test void expiredPendingIsDeletedAndKeyIsReusable() { ... }
@Test void completeFailsWhenPendingWasReaped() { ... }
@Test void reaperRunsOnStartup() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl core -Dtest=CommandRuntimeTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- 迁移加 `lease_until TIMESTAMPTZ(6) NOT NULL` 与索引 `(status, lease_until)`。
- `PostgresCommandStore.expirePending`：`DELETE FROM command_record WHERE status='pending' AND lease_until < ?`。
- `InMemoryCommandStore` 同样。
- `CommandRuntime` 构造 PENDING 时写 lease；`complete` 0 行 → `IllegalStateException`，事务回滚。
- `CommandReaper` 用 `CommandStore`，不直接碰 SQL；`@ConditionalOnProperty(prefix="seedj.command", name="reaper-enabled", havingValue="true", matchIfMissing=true)`。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl core -Dtest=CommandRuntimeTest test`
Run: `./mvnw -o -pl seedj-biz-starter -am -Dtest=CommandReaperTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/cqrs/CommandRecord.java \
        core/src/main/java/cn/byteforce/seedj/cqrs/spi/CommandStore.java \
        core/src/main/java/cn/byteforce/seedj/cqrs/CommandRuntime.java \
        seedj-infra-local/src/main/java/cn/byteforce/seedj/infra/local/cqrs/InMemoryCommandStore.java \
        seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/cqrs \
        seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/wiring/CommandReaper.java \
        seedj-biz-starter/src/test/java/cn/byteforce/seedj/starter/cqrs/wiring/CommandReaperTest.java
git commit -m "fix(cqrs): lease pending commands and reap expired ones"
```

---

### Task 17: fail-safe 配置、索引、审计资源

**Files:**
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/config/DegradedModeGuard.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/wiring/TemporalCommandAutoConfiguration.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/wiring/CommandAgentAutoConfiguration.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/BizSecurityAutoConfiguration.java`（删旧 dev-auth guard，改用统一 guard）
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/domain/TemporalTable.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/domain/TemporalDdl.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/audit/TemporalCommandAuditAdapter.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/TemporalCommandAudit.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/starter/config/DegradedModeGuardTest.java`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/infra/jdbc/domain/TemporalDdlTest.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/starter/audit/TemporalCommandAuditAdapterTest.java`

**Interfaces:**
- `DegradedModeGuard`：`local`/`test` 之外出现 `seedj.command.degraded-mode=true` 或 `seedj.biz.dev-auth.enabled=true` → 启动失败。
- `TemporalTable` 增加 `sortableColumns`、`indexedColumns`（`Set<String>`）；`from(schema)` 填充。
- `TemporalDdl` 为每个 `indexedColumns` 生成 `CREATE INDEX idx_<table>_<field> ON <table> (<field>) WHERE superseded_at IS NULL`。
- `TemporalCommandAudit.accepted(CommandRecord, String objectType, List<CommandEvent>)`；adapter 用 `objectType`，不再截断命令类型。

- [ ] **Step 1: 写失败测试**

```java
@Test void degradedModeFailsOutsideLocalAndTest() { ... }
@Test void devAuthFailsInStaging() { ... }
@Test void ddlCreatesIndexForIndexedField() { ... }
@Test void auditUsesObjectTypeNotCommandPrefix() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl seedj-biz-starter -am -Dtest=DegradedModeGuardTest test`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `DegradedModeGuard` 实现 `InitializingBean`，接受 `Environment`；允许 profile 从 `seedj.degraded.allowed-profiles` 读，默认 `local,test`。
- `CommandAgentAutoConfiguration` 缺 `PlatformTransactionManager` 且非 degraded → 抛异常；degraded 也要求 profile 守卫。
- `TemporalTable` 的 `from` 读 `FieldDefinition.sortable/indexed`。
- `TemporalDdl` 在三个时间轴索引后追加字段索引。
- 审计接口签名变更；`TemporalCommandAutoConfiguration` 传入 `registration.descriptor().objectType()`。

- [ ] **Step 4: 运行确认通过**

Run: `./mvnw -o -pl seedj-biz-starter -am -Dtest=DegradedModeGuardTest,TemporalCommandAuditAdapterTest test`
Run: `./mvnw -o -pl seedj-jdbc-postgresql -am -Dtest=TemporalDdlTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/config \
        seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/cqrs/wiring \
        seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/BizSecurityAutoConfiguration.java \
        seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/domain/TemporalTable.java \
        seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/domain/TemporalDdl.java \
        seedj-biz-starter/src/main/java/cn/byteforce/seedj/starter/audit/TemporalCommandAuditAdapter.java \
        core/src/main/java/cn/byteforce/seedj/cqrs/TemporalCommandAudit.java \
        seedj-*/src/test/java
git commit -m "fix(starter): fail closed on degraded mode and index business fields"
```

---

### Task 18: 文档、ArchUnit 与最终门禁

**Files:**
- Modify: `README.md`（quickstart 补 `idempotency_key`，删除 `employee_id`）
- Modify: `docs/command-contract.md`、`docs/read-model.md`、`docs/security.md`、`core/README.md`
- Modify: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/ArchitectureTest.java`（业务包不得依赖 `TemporalStore`）
- Modify: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/PublicApiSurfaceTest.java`（如需要）
- Modify: `config/public-api.txt`
- Test: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/ArchitectureTest.java`
- Test: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/DocLinkTest.java`

**Interfaces:**
- Consumes: 全部前置任务。
- Produces：绿的 `verify.sh`；`README` quickstart 可复制执行。

- [ ] **Step 1: 写失败测试**

在 `ArchitectureTest` 增加：
```java
@ArchTest
static final ArchRule businessCodeDoesNotUseTemporalStore =
        noClasses().that().resideInAnyPackage("cn.byteforce.seedj.server..",
                        "cn.byteforce.seedj.demo..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("cn.byteforce.seedj.domain.TemporalStore");
```
`DocLinkTest` 已通过；保持。

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -pl seedj-it -am -Dtest=ArchitectureTest test`
Expected: FAIL（HR 或 demo 仍直接依赖 `TemporalStore`）。

- [ ] **Step 3: 实现**

- 修掉剩余直接依赖：`HrEmployeePostgresConfiguration` 只暴露 `TemporalTables`/迁移，不注入 `TemporalStore`；`ObjectQueryController` 走 gateway。
- README quickstart：
  ```bash
  curl -s -X POST localhost:8080/commands/hr.employee.create \
    -H 'Content-Type: application/json' \
    -d '{"type":"hr.employee.create","idempotency_key":"demo-1",
         "payload":{"name":"Alice","hire_date":"2026-01-05",
                    "employment_type":"FULL_TIME","work_location":"SH"}}'
  ```
  以实际 `CommandRequest` 字段为准；删除 `employee_id`。
- 文档更新为新的 `ObjectModuleConfigurer`、`CommandHandler`、`ReadGateway`、`ScopePredicate`、隐私层级；删除 `RequestContext`、旧 `CommandDescriptor` 构造、`object_id`、`Idempotency-Key` header 的漂移。
- `config/public-api.txt` 用 `PublicApiSurfaceTest` 的失败输出逐行更新；业务样板类（`HrEmployee*`）不进入框架 API 清单（如测试允许排除）。

- [ ] **Step 4: 运行最终门禁**

Run: `bash config/ci/verify.sh`
Expected: `verify: OK`（测试、ArchUnit、DocLink、覆盖率、Spotless 全绿）。

- [ ] **Step 5: 提交**

```bash
git add README.md docs core/README.md \
        seedj-it/src/test/java/cn/byteforce/seedj/conformance \
        config/public-api.txt
git commit -m "docs: align contracts with scoped read gateway and module DSL"
```

---

## Self-Review

**Spec coverage:** 读路径（Task 1-10）、写路径 DX（Task 11-14）、P0（Task 15-17）、文档/门禁（Task 18）逐节对应 spec §3-§8。spec §11 的未决问题未排期，符合“不阻塞”。

**Type consistency:** `ScopePredicate` 在 Task 1 定义；`TemporalStore` 在 Task 2 使用；`ReadGateway` 在 Task 7 使用；`CommandContext` 在 Task 11 定义、Task 12 使用；`CommandRegistration` 在 Task 12 定义、Task 13 构造。命名一致（`scope`、`writeScope`、`FieldAccess.Action`）。

**Review Focus 落点:**
1. 未声明 resolver → Task 7 `missingResolverReturns403WithoutTouchingStore`。
2. 无法编译的策略 → Task 9 `unsupportedFieldToFieldComparisonFails`、`unknownEnvironmentKeyFails`。
3. `Not` + NULL → Task 1 `notMatchesNullField`、Task 3 `notUsesIsNotTrue` + 契约测试。
4. `IN` 上限 / 重复 key → Task 1 `inRejectsMoreThanOneThousandValues`、Task 5 `duplicateKeyFails`。
5. 幂等重放 / 崩溃恢复 → Task 12 `replayDoesNotCallTargetId`、Task 16 `expiredPendingIsDeletedAndKeyIsReusable`。

**Proportion:** 计划约 1,100 行，spec 约 690 行；差异主要来自 18 个任务的测试与验证步骤，未内联实现体。
