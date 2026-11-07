package cn.byteforce.seedj.infra.local.id;

import cn.byteforce.seedj.id.IdErrorCode;
import cn.byteforce.seedj.id.IdException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 多服务节点编号分配器：在共享状态文件里按 {@code next} 轮转分配 0..1023。
 *
 * <p>与 PKI / Transit 的状态文件同构：同机多个服务共享同一文件即可拿到互不相同的 节点编号，避免雪花 ID 碰撞。显式配置了 {@code seedj.id.node-id}
 * 时不走本类。
 */
final class NodeIdAllocator {

    static final int MAX_NODE_ID = (1 << 10) - 1;

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS =
            new ConcurrentHashMap<>();
    private static final String NEXT_KEY = "next";

    private final Path file;

    NodeIdAllocator(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    int allocate() {
        Path parent = file.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IdException(
                        IdErrorCode.STATE_UNAVAILABLE,
                        "cannot create node-id directory: " + parent,
                        e);
            }
        }

        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(file, k -> new ReentrantLock());
        localLock.lock();
        try (FileChannel channel =
                        FileChannel.open(
                                file,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {

            Properties props = new Properties();
            long size = channel.size();
            if (size > 0) {
                byte[] data = new byte[(int) size];
                ByteBuffer buf = ByteBuffer.wrap(data);
                channel.position(0);
                while (buf.hasRemaining() && channel.read(buf) >= 0) {
                    // read until EOF
                }
                props.load(new ByteArrayInputStream(data));
            }

            long next =
                    props.containsKey(NEXT_KEY) ? Long.parseLong(props.getProperty(NEXT_KEY)) : 0L;
            if (next > MAX_NODE_ID) {
                throw new IdException(
                        IdErrorCode.NODE_ID_EXHAUSTED,
                        "all node ids are in use, configure seedj.id.node-id explicitly");
            }
            int allocated = (int) next;
            props.setProperty(NEXT_KEY, Long.toString(next + 1));

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            props.store(bos, "seedj node id allocation");
            byte[] out = bos.toByteArray();
            channel.truncate(0);
            channel.position(0);
            ByteBuffer buf = ByteBuffer.wrap(out);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
            return allocated;
        } catch (IOException e) {
            throw new IdException(
                    IdErrorCode.STATE_UNAVAILABLE, "cannot allocate node id from " + file, e);
        } finally {
            localLock.unlock();
        }
    }
}
