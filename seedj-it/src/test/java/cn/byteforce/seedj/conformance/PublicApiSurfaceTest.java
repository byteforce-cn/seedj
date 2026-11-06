package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 公共 API 门禁：每个 public 顶层类型都必须显式登记在 {@code config/public-api.txt}。
 *
 * <p>清单是 allowlist，不是文档：新增类型必须改清单并在评审中可见，删除类型要按
 * {@code docs/api-compatibility.md} 的流程走。格式 {@code module|package|SimpleName}，
 * 按行排序。开发者确认变更后运行 {@code -Dseedj.api.update=true} 写回并提交。</p>
 */
class PublicApiSurfaceTest {

    private static final String PUBLIC_API = "config/public-api.txt";

    @Test
    @DisplayName("公共 API 清单与编译产物完全一致")
    void publicApiMatchesSnapshot() throws IOException {
        List<String> actual = currentSurface();
        Path snapshot = ModuleClassPath.root().resolve(PUBLIC_API);

        if (Boolean.getBoolean("seedj.api.update")) {
            Files.write(snapshot, (String.join("\n", actual) + "\n").getBytes());
            return;
        }

        assertThat(Files.isRegularFile(snapshot))
                .as("%s 不存在；运行 -Dseedj.api.update=true 生成并提交", PUBLIC_API)
                .isTrue();
        List<String> expected = Files.readAllLines(snapshot).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        List<String> added = new ArrayList<>(actual);
        added.removeAll(expected);
        List<String> removed = new ArrayList<>(expected);
        removed.removeAll(actual);

        assertThat(added).as("未登记的 public 类型（新增 API 需显式登记）").isEmpty();
        assertThat(removed).as("清单中已不存在的类型（删除 API 需走兼容性流程）").isEmpty();
    }

    private static List<String> currentSurface() {
        List<String> surface = new ArrayList<>();
        for (String module : ModuleClassPath.MODULES) {
            for (JavaClass clazz : ModuleClassPath.classesOf(module)) {
                if (!clazz.getModifiers().contains(JavaModifier.PUBLIC)
                        || clazz.getName().contains("$")) {
                    continue;
                }
                surface.add(module + "|" + clazz.getPackageName() + "|" + clazz.getSimpleName());
            }
        }
        surface.sort(null);
        return surface;
    }
}
