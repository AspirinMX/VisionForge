package app.pagefault.visionforge.common.config;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;

import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicRuntimeConfigTest {

    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    void localFallbackSeedsDefaultsAndHotReloadsValidJson() {
        NacosSettings settings = new NacosSettings(false, "127.0.0.1:8848", "public", "VISIONFORGE_GROUP", "", "", 5000);
        LocalConfigGateway gateway = new LocalConfigGateway();
        DynamicRuntimeConfig config = new DynamicRuntimeConfig(settings, gateway, Clock.fixed(NOW, ZoneOffset.UTC));

        config.init();

        assertThat(config.initialCreditUnits(10_000L)).isEqualTo(10_000L);
        assertThat(config.rateLimitAuthPerMinute(20)).isEqualTo(20);
        assertThat(config.registrationEnabled(true)).isTrue();
        assertThat(config.listEntries()).hasSize(4);

        DynamicRuntimeConfig.ConfigEntry updated = config.publish(
                DynamicRuntimeConfig.DATA_ID_SECURITY,
                "{\"registrationEnabled\":false,\"rateLimitAuthPerMinute\":5}");

        assertThat(updated.source()).isEqualTo("LOCAL_FALLBACK");
        assertThat(config.registrationEnabled(true)).isFalse();
        assertThat(config.rateLimitAuthPerMinute(20)).isEqualTo(5);

        assertThatThrownBy(() -> config.publish(DynamicRuntimeConfig.DATA_ID_SECURITY, "[1,2,3]"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_ARGUMENT));

        config.reset(DynamicRuntimeConfig.DATA_ID_SECURITY);
        assertThat(config.registrationEnabled(true)).isTrue();
    }

    @Test
    void nacosGatewayBridgesInitialLoadPushListenerAndPublish() {
        NacosSettings settings = new NacosSettings(true, "nacos:8848", "public", "VISIONFORGE_GROUP", "nacos", "nacos", 3000);
        Map<String, String> remoteStore = new ConcurrentHashMap<>();
        remoteStore.put(DynamicRuntimeConfig.DATA_ID_BILLING, "{\"initialCreditUnits\":50000}");
        AtomicReference<Listener> billingListener = new AtomicReference<>();
        AtomicBoolean shutDownCalled = new AtomicBoolean(false);

        ConfigService nacosClient = (ConfigService) Proxy.newProxyInstance(
                ConfigService.class.getClassLoader(),
                new Class<?>[]{ConfigService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServerStatus" -> "UP";
                    case "getConfig" -> remoteStore.get((String) args[0]);
                    case "publishConfig" -> {
                        remoteStore.put((String) args[0], (String) args[2]);
                        yield true;
                    }
                    case "addListener" -> {
                        if (DynamicRuntimeConfig.DATA_ID_BILLING.equals(args[0])) {
                            billingListener.set((Listener) args[2]);
                        }
                        yield null;
                    }
                    case "shutDown" -> {
                        shutDownCalled.set(true);
                        yield null;
                    }
                    default -> null;
                });

        NacosConfigGateway gateway = new NacosConfigGateway(settings, nacosClient);
        DynamicRuntimeConfig config = new DynamicRuntimeConfig(settings, gateway, Clock.fixed(NOW, ZoneOffset.UTC));
        config.init();

        assertThat(config.initialCreditUnits(10_000L)).isEqualTo(50_000L);
        assertThat(billingListener.get()).isNotNull();

        billingListener.get().receiveConfigInfo("{\"initialCreditUnits\":88000}");
        assertThat(config.initialCreditUnits(10_000L)).isEqualTo(88_000L);

        config.publish(DynamicRuntimeConfig.DATA_ID_RUNTIME, "{\"streamTimeoutMs\":600000}");
        assertThat(remoteStore.get(DynamicRuntimeConfig.DATA_ID_RUNTIME)).contains("600000");
        assertThat(config.streamTimeoutMs(300_000L)).isEqualTo(600_000L);

        config.close();
        assertThat(shutDownCalled.get()).isTrue();
    }
}
