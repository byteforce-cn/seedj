package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.server.hr.HrServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 命令目录门禁：运行期 {@code CommandCatalog} 与 {@code config/conformance/commands.yaml}
 * 必须一一对应。
 *
 * <p>YAML 是评审可见的登记表；catalog 是真正执行的注册表。只改代码不改登记表、或只改
 * 登记表不改代码，都会在测试里失败。</p>
 */
@SpringBootTest(classes = HrServer.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.profiles.active=local")
class CommandApiConformanceTest {

    private static final Path ROOT = NamingGuardTest.repoRoot(
            Path.of(System.getProperty("user.dir")));

    private static final Path REGISTRY = ROOT.resolve("config/conformance/commands.yaml");

    @Autowired
    private CommandCatalog catalog;

    @Test
    @DisplayName("命令目录与 commands.yaml 完全一致")
    void catalogMatchesRegistry() throws IOException {
        assertThat(Files.isRegularFile(REGISTRY))
                .as("%s 不存在；先登记命令再提交", REGISTRY)
                .isTrue();

        Map<String, Map<String, String>> registered = parse(Files.readAllLines(REGISTRY));
        List<CommandDescriptor> descriptors = catalog.descriptors();
        Map<String, CommandDescriptor> byType = new LinkedHashMap<>();
        for (CommandDescriptor descriptor : descriptors) {
            CommandDescriptor previous = byType.put(descriptor.type(), descriptor);
            assertThat(previous).as("运行期命令类型重复：%s", descriptor.type()).isNull();
        }

        assertThat(registered.keySet())
                .as("commands.yaml 与运行期命令类型集合不一致")
                .isEqualTo(byType.keySet());

        for (CommandDescriptor descriptor : descriptors) {
            Map<String, String> entry = registered.get(descriptor.type());
            assertThat(entry.get("permission"))
                    .as("%s 的 permission", descriptor.type())
                    .isEqualTo(descriptor.permission());
            assertThat(entry.get("objectType"))
                    .as("%s 的 objectType", descriptor.type())
                    .isEqualTo(descriptor.objectType());
            assertThat(entry.get("payloadSchema"))
                    .as("%s 的 payloadSchema", descriptor.type())
                    .isEqualTo(descriptor.payloadType().getSimpleName());
            assertThat(entry.get("eventTypes"))
                    .as("%s 的 eventTypes", descriptor.type())
                    .isNotBlank();
        }
    }

    /** 解析 commands.yaml 的最小结构；不引入 YAML 依赖到测试模块。 */
    private static Map<String, Map<String, String>> parse(List<String> lines) {
        Map<String, Map<String, String>> entries = new LinkedHashMap<>();
        Map<String, String> current = null;
        for (String raw : lines) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("- ")) {
                current = new LinkedHashMap<>();
                put(current, trimmed.substring(2));
                String type = current.get("type");
                if (type != null) {
                    entries.put(type, current);
                }
            } else if (current != null && trimmed.contains(":")) {
                put(current, trimmed);
            }
        }
        return entries;
    }

    private static void put(Map<String, String> entry, String pair) {
        int colon = pair.indexOf(':');
        if (colon < 0) {
            return;
        }
        String key = pair.substring(0, colon).trim();
        String value = pair.substring(colon + 1).trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        entry.put(key, value);
    }
}
