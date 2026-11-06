package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.core.security.DerivedFieldKeyRing;
import cn.byteforce.seedj.core.security.DerivedFieldRegistry;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandPayloadRedactor;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandValidator;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.id.Uuid7IdGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HR 员工活样板装配：schema、派生字段、命令描述与两个命令代理。
 *
 * <p>字段只在 {@link HrEmployeeSchema} 声明一次；权限、校验、脱敏声明在 descriptor 上，由
 * {@code CommandCatalog} 统一执行。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class HrEmployeeConfiguration {

    /**
     * 员工对象 schema。
     *
     * @return schema
     */
    @Bean
    public ObjectSchema hrEmployeeSchema() {
        return new HrEmployeeSchema().schema();
    }

    /**
     * 证件散列派生：优先 {@code seedj.security.derived-field-keys}，否则读环境变量。
     *
     * <p>两者都缺失时启动失败；生产不得回退到测试密钥。</p>
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
     * 逻辑名索引的派生字段，避免多个 {@link DerivedField} bean 注入歧义。
     *
     * @param idNumberHash 证件散列派生
     * @return 注册表
     */
    @Bean
    public DerivedFieldRegistry hrDerivedFields(
            @Qualifier("hrIdNumberHash") DerivedField idNumberHash) {
        return new DerivedFieldRegistry(Map.of(HrEmployeeFields.ID_NUMBER_HASH, idNumberHash));
    }

    /**
     * 命令记录脱敏：原始证件号与派生散列都不落命令记录。
     *
     * @return 脱敏器
     */
    @Bean
    public CommandPayloadRedactor hrPayloadRedactor() {
        return payload -> {
            Map<String, Object> redacted = new LinkedHashMap<>(payload);
            redacted.remove("id_number");
            redacted.remove("id_number_hash");
            return redacted;
        };
    }

    /**
     * 建档命令描述。
     *
     * @param validators 命令校验器（存在 Bean Validation 时自动使用）
     * @param redactor   命令记录脱敏
     * @return 命令描述
     */
    @Bean
    public CommandDescriptor hrCreateDescriptor(ObjectProvider<CommandValidator> validators,
                                                CommandPayloadRedactor redactor) {
        return CommandDescriptor.builder()
                .type("hr.employee.create")
                .objectType("employee")
                .payloadType(HrEmployeeCreate.class)
                .permission("hr.employee.write")
                .validator(validators.getIfAvailable(CommandValidator::none))
                .redactor(redactor)
                .build();
    }

    /**
     * 更新命令描述。
     *
     * @param validators 命令校验器
     * @param redactor   命令记录脱敏
     * @return 命令描述
     */
    @Bean
    public CommandDescriptor hrUpdateDescriptor(ObjectProvider<CommandValidator> validators,
                                                CommandPayloadRedactor redactor) {
        return CommandDescriptor.builder()
                .type("hr.employee.update")
                .objectType("employee")
                .payloadType(HrEmployeeUpdate.class)
                .permission("hr.employee.write")
                .validator(validators.getIfAvailable(CommandValidator::none))
                .redactor(redactor)
                .build();
    }

    /**
     * 工号生成器：基于时间有序的 UUIDv7，避免进程内 {@code AtomicLong} 生成持久业务标识。
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
     * 员工读模型查询服务。
     *
     * @param store  对象存储
     * @param schema 员工 schema
     * @return 查询服务
     */
    @Bean
    public HrEmployeeQueryService hrEmployeeQueryService(TemporalStore store,
                                                        ObjectSchema schema) {
        return new HrEmployeeQueryService(store, schema);
    }

    /**
     * 建档代理。
     *
     * @param runtime    命令运行时
     * @param descriptor 建档命令描述
     * @param schema     员工 schema
     * @param derived    派生字段注册表
     * @param ids        工号生成器
     * @return 代理
     */
    @Bean
    public HrEmployeeCreateAgent hrEmployeeCreateAgent(
            CommandRuntime runtime,
            @Qualifier("hrCreateDescriptor") CommandDescriptor descriptor,
            ObjectSchema schema,
            DerivedFieldRegistry derived,
            @Qualifier("hrEmployeeNoGenerator") IdGenerator<String> ids) {
        return new HrEmployeeCreateAgent(runtime, descriptor, schema, derived, ids);
    }

    /**
     * 更新代理。
     *
     * @param runtime    命令运行时
     * @param descriptor 更新命令描述
     * @param schema     员工 schema
     * @param derived    派生字段注册表
     * @return 代理
     */
    @Bean
    public HrEmployeeUpdateAgent hrEmployeeUpdateAgent(
            CommandRuntime runtime,
            @Qualifier("hrUpdateDescriptor") CommandDescriptor descriptor,
            ObjectSchema schema,
            DerivedFieldRegistry derived) {
        return new HrEmployeeUpdateAgent(runtime, descriptor, schema, derived);
    }
}
