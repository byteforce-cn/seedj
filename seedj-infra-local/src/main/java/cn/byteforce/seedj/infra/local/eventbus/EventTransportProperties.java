package cn.byteforce.seedj.infra.local.eventbus;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地队列（transport）配置。
 *
 * <p>只描述"事件发到哪里"；是否需要 outbox 由 {@code seedj-jdbc-postgresql} 的配置决定。
 */
@ConfigurationProperties(prefix = "seedj.eventbus")
public class EventTransportProperties {

    /** 本地队列实现：{@code file}（默认，磁盘）或 {@code memory}（进程内）。 */
    private String transport = "file";

    private final File file = new File();

    private final Memory memory = new Memory();

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public File getFile() {
        return file;
    }

    public Memory getMemory() {
        return memory;
    }

    public static class File {

        /** 队列目录；同机多服务指向同一目录即共享队列。 */
        private Path baseDir = Path.of(System.getProperty("java.io.tmpdir"), "seedj-eventbus");

        public Path getBaseDir() {
            return baseDir;
        }

        public void setBaseDir(Path baseDir) {
            this.baseDir = baseDir;
        }
    }

    public static class Memory {

        /** 进程内队列容量；写满后丢弃最旧记录。 */
        private int capacity = 10_000;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
    }
}
