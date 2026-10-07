package cn.byteforce.seedj.starter.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * seedj Jackson 定制契约测试（P0 护栏）：
 * 自动配置注册、CodeEnum code 往返、ordinal 静默映射防护、Long 字符串化开关。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class SeedjJacksonAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SeedjJacksonAutoConfiguration.class));

    @Test
    void autoConfigurationIsRegisteredInImportsFile() throws IOException {
        Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        List<String> contents = new ArrayList<>();
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            try (var in = url.openStream()) {
                contents.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(contents).anySatisfy(content ->
                assertThat(content).contains(SeedjJacksonAutoConfiguration.class.getName()));
    }

    @Test
    void registersCustomizerBeanByDefault() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(JsonMapperBuilderCustomizer.class));
    }

    @Test
    void killSwitchDisablesAllCustomization() {
        runner.withPropertyValues("byteforce.jackson.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(JsonMapperBuilderCustomizer.class));
    }

    @Test
    void serializesCodeEnumAsCode() {
        SamplePayload payload = new SamplePayload();
        payload.state = SampleState.REVOKED;
        payload.id = 7L;
        payload.primitiveId = 8L;

        JsonNode node = mapper(new SeedjJacksonProperties()).readTree(
                mapper(new SeedjJacksonProperties()).writeValueAsString(payload));

        assertThat(node.get("state").asInt()).isEqualTo(200);
        assertThat(node.get("id").asString()).isEqualTo("7");
        assertThat(node.get("primitiveId").asString()).isEqualTo("8");
    }

    @Test
    void deserializesCodeEnumByCode() {
        SamplePayload payload = mapper(new SeedjJacksonProperties())
                .readValue("{\"state\":100}", SamplePayload.class);

        assertThat(payload.state).isEqualTo(SampleState.CREATED);
    }

    @Test
    void roundTripKeepsCodeSemantics() {
        JsonMapper mapper = mapper(new SeedjJacksonProperties());
        SamplePayload payload = new SamplePayload();
        payload.state = SampleState.CREATED;

        String json = mapper.writeValueAsString(payload);
        SamplePayload restored = mapper.readValue(json, SamplePayload.class);

        assertThat(restored.state).isEqualTo(SampleState.CREATED);
    }

    @Test
    void numericInputMustNotSilentlyMapByOrdinal() {
        JsonMapper mapper = mapper(new SeedjJacksonProperties());

        // ordinal 1 恰好是 REVOKED、ordinal 0 是 CREATED；输入 1 不是任何 code，必须报错
        assertThatThrownBy(() -> mapper.readValue("{\"state\":1}", SamplePayload.class))
                .isInstanceOf(InvalidFormatException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"state\":0}", SamplePayload.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void unknownCodeFailsFast() {
        JsonMapper mapper = mapper(new SeedjJacksonProperties());

        assertThatThrownBy(() -> mapper.readValue("{\"state\":999}", SamplePayload.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void nullEnumStaysNull() {
        SamplePayload payload = mapper(new SeedjJacksonProperties())
                .readValue("{\"state\":null}", SamplePayload.class);

        assertThat(payload.state).isNull();
    }

    @Test
    void longAsStringCanBeDisabled() {
        SeedjJacksonProperties properties = new SeedjJacksonProperties();
        properties.setLongAsString(false);

        JsonMapper mapper = mapper(properties);
        SamplePayload payload = new SamplePayload();
        payload.id = 7L;
        payload.primitiveId = 8L;

        JsonNode node = mapper.readTree(mapper.writeValueAsString(payload));

        assertThat(node.get("id").isNumber()).isTrue();
        assertThat(node.get("id").asLong()).isEqualTo(7L);
        assertThat(node.get("primitiveId").asLong()).isEqualTo(8L);
    }

    @Test
    void longAcceptsBothStringAndNumberOnRead() {
        JsonMapper mapper = mapper(new SeedjJacksonProperties());

        SamplePayload fromString = mapper.readValue("{\"id\":\"123\"}", SamplePayload.class);
        SamplePayload fromNumber = mapper.readValue("{\"id\":123}", SamplePayload.class);

        assertThat(fromString.id).isEqualTo(123L);
        assertThat(fromNumber.id).isEqualTo(123L);
    }

    private JsonMapper mapper(SeedjJacksonProperties properties) {
        JsonMapper.Builder builder = JsonMapper.builder();
        new SeedjJacksonAutoConfiguration()
                .seedjJsonMapperBuilderCustomizer(properties)
                .customize(builder);
        return builder.build();
    }
}
