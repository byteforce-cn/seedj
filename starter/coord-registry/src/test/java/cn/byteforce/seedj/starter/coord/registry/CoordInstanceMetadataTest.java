package cn.byteforce.seedj.starter.coord.registry;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoordInstanceMetadata} JSON 构建测试（约定 C1）。
 */
class CoordInstanceMetadataTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void buildsStandardSchemaWithDefaults() {
        CoordRegistryProperties properties = new CoordRegistryProperties();

        JsonNode node = mapper.readTree(CoordInstanceMetadata.toJson("10.0.0.1", 8081, properties));

        assertThat(node.get("address").asString()).isEqualTo("10.0.0.1:8081");
        assertThat(node.get("protocol").asString()).isEqualTo("http");
        assertThat(node.get("weight").asInt()).isEqualTo(100);
    }

    @Test
    void mergesUserMetadataAndAllowsOverrides() {
        CoordRegistryProperties properties = new CoordRegistryProperties();
        properties.getMetadata().put("protocol", "grpc");
        properties.getMetadata().put("zone", "az-1");

        JsonNode node = mapper.readTree(CoordInstanceMetadata.toJson("h", 1, properties));

        assertThat(node.get("protocol").asString()).isEqualTo("grpc");
        assertThat(node.get("zone").asString()).isEqualTo("az-1");
    }

    @Test
    void blankProtocolIsOmitted() {
        CoordRegistryProperties properties = new CoordRegistryProperties();
        properties.setProtocol(" ");

        JsonNode node = mapper.readTree(CoordInstanceMetadata.toJson("h", 2, properties));

        assertThat(node.get("protocol")).isNull();
        assertThat(node.get("address").asString()).isEqualTo("h:2");
    }

    @Test
    void parsesBackViaServiceInstanceView() {
        CoordRegistryProperties properties = new CoordRegistryProperties();
        properties.setWeight(7);

        JsonNode node = mapper.readTree(CoordInstanceMetadata.toJson("10.1.1.1", 9000, properties));

        assertThat(node.get("weight").asInt()).isEqualTo(7);
        CoordServiceInstance view = new CoordServiceInstance("s", "i", node.toString());
        assertThat(view.getPort()).isEqualTo(9000);
    }
}
