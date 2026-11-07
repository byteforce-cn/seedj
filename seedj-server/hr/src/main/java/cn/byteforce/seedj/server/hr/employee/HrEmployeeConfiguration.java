package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ContextSink;
import cn.byteforce.seedj.core.security.ContextWriter;
import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.core.security.DerivedFieldKeyRing;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * HR 员工活样板装配：派生字段、组织目录、请求事实与两个 handler。
 *
 * <p>字段只在 {@link HrEmployeeModule} 声明一次；schema 从冻结注册表取。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class HrEmployeeConfiguration {

    /**
     * 证件散列派生：优先 {@code seedj.security.derived-field-keys}，否则读环境变量。
     *
     * @param keys 逗号分隔的密钥（local 用 local-dev-key）
     * @return 派生字段
     */
    @Bean
    public DerivedField hrIdNumberHash(
            @Value("${seedj.security.derived-field-keys:}") String keys) {
        DerivedFieldKeyRing keyRing = keys == null || keys.isBlank()
                ? DerivedFieldKeyRing.fromEnvironment()
                : new DerivedFieldKeyRing(keys.split(","));
        return new DerivedField("seedj:hr:employee:id_number_hash:v1", keyRing);
    }

    /**
     * 逻辑名索引的派生字段。
     *
     * @param idNumberHash 证件散列派生
     * @return 注册表
     */
    @Bean
    public DerivedFieldRegistry hrDerivedFields(
            @Qualifier("hrIdNumberHash") DerivedField idNumberHash) {
        return new DerivedFieldRegistry(Map.of(HrEmployeeCreateHandler.ID_NUMBER_HASH,
                idNumberHash));
    }

    /**
     * 工号生成器：基于时间有序的 UUIDv7。
     *
     * @param clock 取时入口
     * @return 工号生成器
     */
    @Bean
    public IdGenerator<String> hrEmployeeNoGenerator(Clock clock) {
        IdGenerator<UUID> uuid7 = new Uuid7IdGenerator(clock);
        return () -> uuid7.id().toString();
    }

    /**
     * 组织单元目录：样例从配置读取；生产替换为组织服务。
     *
     * @param units 逗号分隔的组织单元
     * @return 目录
     */
    @Bean
    public OrgUnitDirectory hrOrgUnitDirectory(
            @Value("${seedj.hr.org-units:D01,D02,D03}") String units) {
        Set<String> known = units == null || units.isBlank()
                ? Set.of()
                : java.util.Arrays.stream(units.split(",")).map(String::trim)
                        .filter(unit -> !unit.isBlank()).collect(java.util.stream.Collectors.toSet());
        return known::contains;
    }

    /**
     * 员工读范围：从调用方属性 {@code org_units} 写入请求事实。
     *
     * @return writer
     */
    @Bean
    public ContextWriter hrOrgUnitContextWriter() {
        return (ContextSink sink, Caller caller) ->
                sink.put(HrScopeKeys.ORG_UNITS, HrScopeKeys.callerOrgUnits(caller));
    }

    /**
     * 员工读范围解析器。
     *
     * @return resolver
     */
    @Bean
    public HrOrgUnitScopeResolver hrOrgUnitScopeResolver() {
        return new HrOrgUnitScopeResolver();
    }

    /**
     * 员工模块声明：纯类，不依赖 Spring 容器，测试可直接 new。
     *
     * @param create 建档处理器
     * @param update 更新处理器
     * @return 模块声明
     */
    @Bean
    public HrEmployeeModule hrEmployeeModule(HrEmployeeCreateHandler create,
                                             HrEmployeeUpdateHandler update) {
        return new HrEmployeeModule(create, update);
    }

    /**
     * 建档处理器。
     *
     * @param commandIds 目标对象 ID 生成器
     * @param employeeNos 工号生成器
     * @param derived    派生字段
     * @param orgUnits   组织目录
     * @return 处理器
     */
    @Bean
    public HrEmployeeCreateHandler hrEmployeeCreateHandler(
            @Qualifier("commandIdGenerator") IdGenerator<UUID> commandIds,
            @Qualifier("hrEmployeeNoGenerator") IdGenerator<String> employeeNos,
            DerivedFieldRegistry derived, OrgUnitDirectory orgUnits) {
        return new HrEmployeeCreateHandler(commandIds, employeeNos, derived, orgUnits);
    }

    /**
     * 更新处理器。
     *
     * @param derived  派生字段
     * @param orgUnits 组织目录
     * @return 处理器
     */
    @Bean
    public HrEmployeeUpdateHandler hrEmployeeUpdateHandler(DerivedFieldRegistry derived,
                                                           OrgUnitDirectory orgUnits) {
        return new HrEmployeeUpdateHandler(derived, orgUnits);
    }
}
