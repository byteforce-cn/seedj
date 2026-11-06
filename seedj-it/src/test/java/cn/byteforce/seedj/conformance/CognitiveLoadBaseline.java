package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 认知负担基线读取器。
 *
 * <p>门禁只允许基线收紧：测量值超过基线即失败；测量值改善时打印提示（不失败），
 * 由改动人更新 {@code config/cognitive-load-baseline.json}。基线文件与测试同批入库，
 * CI 默认执行。</p>
 */
final class CognitiveLoadBaseline {

    static final String FILE = "config/cognitive-load-baseline.properties";

    private CognitiveLoadBaseline() {
    }

    static int intValue(String key) {
        String value = read().getProperty(key);
        if (value == null) {
            throw new IllegalStateException("missing baseline " + key + " in " + FILE);
        }
        return Integer.parseInt(value.trim());
    }

    /**
     * 断言测量值不超过基线，并提示收紧。
     *
     * @param label    指标名
     * @param key      基线键
     * @param measured 测量值
     */
    static void assertWithin(String label, String key, int measured) {
        int baseline = intValue(key);
        assertThat(measured)
                .as("%s = %d 超过基线 %d；先降低指标，确有理由再改 %s", label, measured,
                        baseline, FILE)
                .isLessThanOrEqualTo(baseline);
        if (measured < baseline) {
            System.out.printf("cognitive-load: %s improved %d -> %d; tighten %s%n",
                    label, baseline, measured, key);
        }
    }

    static List<Path> javaFiles(Path root, String relativeDirectory) {
        Path directory = root.resolve(relativeDirectory);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static long lineCount(List<Path> files) {
        long total = 0;
        for (Path file : files) {
            try {
                total += Files.lines(file).count();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        return total;
    }

    static long textOccurrences(List<Path> files, String needle) {
        long total = 0;
        for (Path file : files) {
            try {
                for (String line : Files.readAllLines(file)) {
                    if (line.contains(needle)) {
                        total++;
                    }
                }
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        return total;
    }

    private static Properties read() {
        Path file = ModuleClassPath.root().resolve(FILE);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return properties;
    }
}
