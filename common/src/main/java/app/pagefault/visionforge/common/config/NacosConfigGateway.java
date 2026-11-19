package app.pagefault.visionforge.common.config;

import java.util.Properties;
import java.util.function.Consumer;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.exception.NacosException;

import lombok.extern.slf4j.Slf4j;

/**
 * Official pure-Java Nacos ConfigService bridge supporting gRPC push listeners and graceful degradation.
 */
@Slf4j
public class NacosConfigGateway implements ConfigCenterGateway {

    private final NacosSettings settings;
    private final ConfigService configService;

    public NacosConfigGateway(NacosSettings settings) {
        this.settings = settings;
        this.configService = initConfigService(settings);
    }

    NacosConfigGateway(NacosSettings settings, ConfigService configService) {
        this.settings = settings;
        this.configService = configService;
    }

    @Override
    public String sourceName() {
        return isRemoteActive() ? "NACOS" : "NACOS_DEGRADED";
    }

    @Override
    public boolean isRemoteActive() {
        return configService != null && !"DOWN".equalsIgnoreCase(configService.getServerStatus());
    }

    @Override
    public String getConfig(String dataId) {
        if (configService == null) {
            return null;
        }
        try {
            return configService.getConfig(dataId, settings.group(), settings.timeoutMs());
        } catch (NacosException | RuntimeException exception) {
            log.warn("Failed to fetch Nacos config dataId={} group={}: {}",
                    dataId, settings.group(), exception.getMessage());
            return null;
        }
    }

    @Override
    public boolean publishConfig(String dataId, String content, String type) {
        if (configService == null) {
            return false;
        }
        try {
            return configService.publishConfig(dataId, settings.group(), content, type);
        } catch (NacosException | RuntimeException exception) {
            log.error("Failed to publish Nacos config dataId={} group={}: {}",
                    dataId, settings.group(), exception.getMessage());
            return false;
        }
    }

    @Override
    public void addListener(String dataId, Consumer<String> onUpdate) {
        if (configService == null) {
            return;
        }
        try {
            configService.addListener(dataId, settings.group(), new AbstractListener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    if (configInfo != null && !configInfo.isBlank()) {
                        onUpdate.accept(configInfo);
                    }
                }
            });
        } catch (NacosException | RuntimeException exception) {
            log.warn("Failed to register Nacos listener for dataId={}: {}",
                    dataId, exception.getMessage());
        }
    }

    @Override
    public void close() {
        if (configService == null) {
            return;
        }
        try {
            configService.shutDown();
        } catch (NacosException | RuntimeException exception) {
            log.warn("Error shutting down Nacos ConfigService: {}", exception.getMessage());
        }
    }

    private static ConfigService initConfigService(NacosSettings settings) {
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, settings.serverAddr());
        if (!"public".equalsIgnoreCase(settings.namespace()) && !settings.namespace().isBlank()) {
            props.put(PropertyKeyConst.NAMESPACE, settings.namespace());
        }
        if (!settings.username().isBlank()) {
            props.put(PropertyKeyConst.USERNAME, settings.username());
        }
        if (!settings.password().isBlank()) {
            props.put(PropertyKeyConst.PASSWORD, settings.password());
        }
        try {
            return NacosFactory.createConfigService(props);
        } catch (NacosException | RuntimeException exception) {
            log.error("Could not initialize Nacos ConfigService at {}: {}",
                    settings.serverAddr(), exception.getMessage());
            return null;
        }
    }
}
