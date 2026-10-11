package cn.byteforce.seedj.starter.eis.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件锚定：以追加行写入链头（{@code sequence\thash\tanchored_at}，UTF-8），
 * 供独立介质/异地归档核验。
 *
 * <p>行格式与验证工具无关（锚定是部署策略面）；核验方式：比对锚定行的
 * hash 与链内同序号条目的 hash。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class FileEisAuditAnchor implements EisAuditAnchor {

    private final Path file;

    /**
     * 构造文件锚定。
     *
     * @param file 锚定文件路径（父目录不存在时自动创建）
     */
    public FileEisAuditAnchor(Path file) {
        this.file = file;
    }

    @Override
    public void anchor(EisAuditHead head) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file,
                    head.getSequence() + "\t" + head.getHash() + "\t" + head.getAnchoredAt() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("审计锚定写入失败: " + file, ex);
        }
    }

    /**
     * 获取锚定文件路径。
     *
     * @return 文件路径
     */
    public Path getFile() {
        return file;
    }
}
