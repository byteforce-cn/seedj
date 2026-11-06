package cn.byteforce.seedj.infra.local.transit;

import cn.byteforce.seedj.transit.KeyType;
import cn.byteforce.seedj.transit.TransitErrorCode;
import cn.byteforce.seedj.transit.TransitException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 基于 {@code Properties} 文件的密钥状态存储，多服务共享同一文件即可共享密钥。
 *
 * <p>文件格式（key 名称用 base64url 编码，避免名称里的点号与属性分隔符冲突）：
 * <pre>
 * key.&lt;b64url(name)&gt;.type=AES256_GCM96
 * key.&lt;b64url(name)&gt;.derived=false
 * key.&lt;b64url(name)&gt;.exportable=false
 * key.&lt;b64url(name)&gt;.convergent=false
 * key.&lt;b64url(name)&gt;.latest=2
 * key.&lt;b64url(name)&gt;.minDec=1
 * key.&lt;b64url(name)&gt;.minEnc=1
 * key.&lt;b64url(name)&gt;.v.1.secret=&lt;base64&gt;
 * key.&lt;b64url(name)&gt;.v.2.priv=&lt;base64&gt;     （非对称）
 * key.&lt;b64url(name)&gt;.v.2.pub=&lt;base64&gt;
 * key.&lt;b64url(name)&gt;.v.2.alg=RSA
 * </pre>
 *
 * <p>并发控制与原子性同 {@code PkiStateFile}：同 JVM 用 {@link ReentrantLock}，
 * 跨进程用 {@link FileLock}；写操作在锁内"读—改—truncate—写—force"。
 */
final class TransitStateFile {

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    private static final String PREFIX = "key.";
    private static final String FILE_HEADER = "seedj Transit state";

    private final Path file;

    TransitStateFile(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    Path path() {
        return file;
    }

    Map<String, StoredKey> read() {
        if (!Files.exists(file)) {
            return Map.of();
        }
        ReentrantLock localLock = localLock();
        localLock.lock();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return decode(readChannel(channel));
        } catch (IOException e) {
            throw new TransitException(TransitErrorCode.INVALID_CONFIGURATION,
                    "读取 Transit 状态文件失败: " + file, e);
        } finally {
            localLock.unlock();
        }
    }

    <T> T update(Function<Map<String, StoredKey>, T> mutation) {
        prepareParentDirectory();
        ReentrantLock localLock = localLock();
        localLock.lock();
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {

            Map<String, StoredKey> state = decode(readChannel(channel));
            T result = mutation.apply(state);
            writeChannel(channel, encode(state));
            return result;
        } catch (IOException e) {
            throw new TransitException(TransitErrorCode.INVALID_CONFIGURATION,
                    "更新 Transit 状态文件失败: " + file, e);
        } finally {
            localLock.unlock();
        }
    }

    // ==================== 编解码 ====================

    private static String nameKey(String name) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decodeName(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Properties encode(Map<String, StoredKey> state) {
        Properties props = new Properties();
        state.forEach((name, key) -> {
            String base = PREFIX + nameKey(name) + ".";
            props.setProperty(base + "type", key.type().name());
            props.setProperty(base + "derived", Boolean.toString(key.derived()));
            props.setProperty(base + "exportable", Boolean.toString(key.exportable()));
            props.setProperty(base + "convergent", Boolean.toString(key.convergentEncryption()));
            props.setProperty(base + "plaintextBackup", Boolean.toString(key.allowPlaintextBackup()));
            props.setProperty(base + "latest", Integer.toString(key.latestVersion()));
            props.setProperty(base + "minDec", Integer.toString(key.minDecryptionVersion()));
            props.setProperty(base + "minEnc", Integer.toString(key.minEncryptionVersion()));
            key.versions().forEach((version, material) -> {
                String vBase = base + "v." + version + ".";
                if (material.isSymmetric()) {
                    props.setProperty(vBase + "secret", material.secretBase64());
                } else {
                    props.setProperty(vBase + "priv", material.privateBase64());
                    props.setProperty(vBase + "pub", material.publicBase64());
                }
                props.setProperty(vBase + "alg", material.algorithm());
            });
        });
        return props;
    }

    private static Map<String, StoredKey> decode(Properties props) {
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(PREFIX)) {
                continue;
            }
            String rest = name.substring(PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot < 0) {
                continue;
            }
            grouped.computeIfAbsent(rest.substring(0, dot), k -> new LinkedHashMap<>())
                    .put(rest.substring(dot + 1), props.getProperty(name));
        }

        Map<String, StoredKey> result = new LinkedHashMap<>();
        grouped.forEach((encodedName, raw) -> {
            String name = decodeName(encodedName);
            KeyType type = KeyType.valueOf(raw.get("type"));
            int latest = Integer.parseInt(raw.get("latest"));
            Map<Integer, KeyMaterial> versions = new LinkedHashMap<>();
            raw.forEach((prop, value) -> {
                if (!prop.startsWith("v.") || !prop.endsWith(".alg")) {
                    return;
                }
                int version = Integer.parseInt(prop.substring(2, prop.length() - 4));
                String base = "v." + version + ".";
                String algorithm = value;
                if (raw.containsKey(base + "secret")) {
                    versions.put(version, new KeyMaterial(
                            Base64.getDecoder().decode(raw.get(base + "secret")),
                            null, null, algorithm));
                } else {
                    versions.put(version, new KeyMaterial(null,
                            Base64.getDecoder().decode(raw.get(base + "priv")),
                            Base64.getDecoder().decode(raw.get(base + "pub")),
                            algorithm));
                }
            });
            result.put(name, new StoredKey(type,
                    Boolean.parseBoolean(raw.get("derived")),
                    Boolean.parseBoolean(raw.get("exportable")),
                    Boolean.parseBoolean(raw.get("convergent")),
                    Boolean.parseBoolean(raw.getOrDefault("plaintextBackup", "false")),
                    Integer.parseInt(raw.get("minDec")),
                    Integer.parseInt(raw.get("minEnc")),
                    latest, versions));
        });
        return result;
    }

    // ==================== 文件读写 ====================

    private void prepareParentDirectory() {
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new TransitException(TransitErrorCode.INVALID_CONFIGURATION,
                    "创建 Transit 状态目录失败: " + parent, e);
        }
    }

    private ReentrantLock localLock() {
        return LOCAL_LOCKS.computeIfAbsent(file, k -> new ReentrantLock());
    }

    private static Properties readChannel(FileChannel channel) throws IOException {
        Properties props = new Properties();
        long size = channel.size();
        if (size == 0) {
            return props;
        }
        byte[] data = new byte[(int) size];
        ByteBuffer buf = ByteBuffer.wrap(data);
        channel.position(0);
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) {
                break;
            }
        }
        props.load(new ByteArrayInputStream(data));
        return props;
    }

    private static void writeChannel(FileChannel channel, Properties props) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        props.store(bos, FILE_HEADER);
        byte[] out = bos.toByteArray();

        channel.truncate(0);
        channel.position(0);
        ByteBuffer buf = ByteBuffer.wrap(out);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        channel.force(true);
    }
}
