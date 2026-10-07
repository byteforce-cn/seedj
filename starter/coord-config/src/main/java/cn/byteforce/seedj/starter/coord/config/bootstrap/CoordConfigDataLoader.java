package cn.byteforce.seedj.starter.coord.config.bootstrap;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.CoordConfig;
import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.seedj.starter.coord.CoordProperties;
import cn.byteforce.seedj.starter.coord.config.CoordPropertySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code coord:} 配置加载器：引导阶段创建一次性 {@link CoordClient}，按前缀
 * {@code list()} 全量快照并生成可变的 {@link CoordPropertySource}（后续由
 * {@code CoordConfigRefreshManager} 原地刷新）。
 *
 * <p>失败语义（C5）：</p>
 * <ul>
 *   <li>可用性类错误（Agent 不可达 / 超时 / 鉴权）+ 非 optional → 启动失败
 *       （{@link ConfigDataResourceNotFoundException}，禁止静默用错配置启动）；</li>
 *   <li>可用性类错误 + {@code optional:coord:} → WARN + 空配置跳过；</li>
 *   <li>配置类错误（参数非法等）→ 一律失败（optional 不豁免，配置错误不是「源不可用」）。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordConfigDataLoader implements ConfigDataLoader<CoordConfigDataResource> {

    private static final Logger log = LoggerFactory.getLogger(CoordConfigDataLoader.class);

    /** 视为「源不可用」的错误码（optional 可豁免；其余一律失败）。 */
    private static final Set<ErrorCode> AVAILABILITY_ERRORS = Set.of(
            ErrorCode.AGENT_UNAVAILABLE,
            ErrorCode.UNAVAILABLE,
            ErrorCode.DEADLINE_EXCEEDED,
            ErrorCode.UNAUTHENTICATED,
            ErrorCode.PERMISSION_DENIED
    );

    @Override
    public ConfigData load(ConfigDataLoaderContext context, CoordConfigDataResource resource) throws IOException {
        CoordClient client = createClient(resource.getConnectionProperties());
        try {
            Map<String, String> entries = new LinkedHashMap<>();
            for (String prefix : resource.getPrefixes()) {
                entries.putAll(client.configClient().list(prefix));
            }
            CoordPropertySource source = CoordPropertySource.forPrefixes(resource.getPrefixes());
            entries.forEach((key, value) -> source.apply(key, Optional.of(value)));
            log.info("coord 配置导入成功：prefixes={}，共 {} 项", resource.getPrefixes(), entries.size());
            return new ConfigData(List.of(source));
        } catch (CoordException e) {
            if (resource.isOptional() && AVAILABILITY_ERRORS.contains(e.getErrorCode())) {
                log.warn("optional:coord: 配置源不可用，已跳过：prefixes={}，原因={}",
                        resource.getPrefixes(), e.getMessage());
                return ConfigData.EMPTY;
            }
            throw new ConfigDataResourceNotFoundException(resource, e);
        } catch (RuntimeException e) {
            // 非可用性错误（连接参数校验失败等）：optional 不豁免——配置错误必须显式暴露
            throw new IOException("coord 配置加载失败（配置错误，optional 不豁免）: " + e.getMessage(), e);
        } finally {
            closeQuietly(client);
        }
    }

    private CoordClient createClient(CoordProperties properties) {
        CoordConfig.Builder builder = properties.toBuilder();
        if (StringUtils.hasText(properties.getAuthToken())) {
            builder.authToken(properties.getAuthToken());
        }
        return CoordClient.create(builder.build());
    }

    private void closeQuietly(CoordClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            log.warn("引导期 CoordClient 关闭失败（已忽略）: {}", e.getMessage());
        }
    }
}
