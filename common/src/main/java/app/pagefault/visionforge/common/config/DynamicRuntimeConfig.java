package app.pagefault.visionforge.common.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds hot-reloaded dynamic runtime configuration synchronized with Nacos (or local fallback).
 */
@Slf4j
public class DynamicRuntimeConfig {

    public static final String DATA_ID_RUNTIME = "visionforge-runtime.json";
    public static final String DATA_ID_SECURITY = "visionforge-security.json";
    public static final String DATA_ID_BILLING = "visionforge-billing.json";
    public static final String DATA_ID_ALERT = "visionforge-alert.json";

    private static final List<ConfigSpec> SPECS = List.of(
            new ConfigSpec(
                    DATA_ID_RUNTIME,
                    "运行期与上下文参数",
                    Map.of(
                            "contextCompactThreshold", 0.8d,
                            "contextCompactKeepRatio", 0.25d,
                            "contextSummaryMaxOutputTokens", 1024,
                            "streamTimeoutMs", 300_000L)),
            new ConfigSpec(
                    DATA_ID_SECURITY,
                    "安全、注册与限流阈值",
                    Map.of(
                            "registrationEnabled", true,
                            "rateLimitEnabled", true,
                            "rateLimitAuthPerMinute", 20,
                            "rateLimitRedeemPerMinute", 10,
                            "rateLimitGenerationPerMinute", 30,
                            "rateLimitUploadPerMinute", 30)),
            new ConfigSpec(
                    DATA_ID_BILLING,
                    "计费与初始额度策略",
                    Map.of(
                            "initialCreditUnits", 10_000L)),
            new ConfigSpec(
                    DATA_ID_ALERT,
                    "邮件与 Webhook 告警开关与 SMTP 参数",
                    Map.of(
                            "alertsEnabled", true,
                            "smtpHost", "",
                            "smtpPort", 587,
                            "smtpUsername", "",
                            "smtpFrom", "",
                            "smtpSslEnabled", true,
                            "webhookAllowPrivateNetwork", false)));

    private final NacosSettings settings;
    private final ConfigCenterGateway gateway;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Map<String, StoredConfig> rawConfigs = new ConcurrentHashMap<>();
    private final AtomicReference<Map<String, Object>> mergedValues = new AtomicReference<>(Map.of());

    public DynamicRuntimeConfig(NacosSettings settings, ConfigCenterGateway gateway, Clock clock) {
        this.settings = settings;
        this.gateway = gateway;
        this.clock = clock;
        this.objectMapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        seedLocalDefaults();
    }

    public void init() {
        for (ConfigSpec spec : SPECS) {
            String remote = gateway.getConfig(spec.dataId());
            if (remote != null && !remote.isBlank()) {
                applyUpdate(spec.dataId(), remote, gateway.sourceName());
            } else {
                String defaultJson = toPrettyJson(spec.defaults());
                if (settings.enabled() && gateway.isRemoteActive()) {
                    gateway.publishConfig(spec.dataId(), defaultJson, "json");
                }
                applyUpdate(spec.dataId(), defaultJson, gateway.sourceName());
            }
            gateway.addListener(spec.dataId(), updated -> applyUpdate(spec.dataId(), updated, "NACOS_PUSH"));
        }
    }

    public void close() {
        gateway.close();
    }

    public List<ConfigEntry> listEntries() {
        List<ConfigEntry> entries = new ArrayList<>(SPECS.size());
        for (ConfigSpec spec : SPECS) {
            entries.add(buildEntry(spec));
        }
        return entries;
    }

    public ConfigEntry getEntry(String dataId) {
        ConfigSpec spec = requireSpec(dataId);
        if (settings.enabled() && gateway.isRemoteActive()) {
            String remote = gateway.getConfig(dataId);
            if (remote != null && !remote.isBlank()) {
                applyUpdate(dataId, remote, "NACOS");
            }
        }
        return buildEntry(spec);
    }

    @BizLog(
            type = "ADMIN_CONFIG",
            subType = "PUBLISH",
            bizNo = "#dataId",
            success = "管理员发布 Nacos 动态配置 {{#dataId}}",
            fail = "发布动态配置 {{#dataId}} 失败：{{#errorMessage}}",
            sync = true)
    public ConfigEntry publish(String dataId, String content) {
        ConfigSpec spec = requireSpec(dataId);
        String formatted = normalizeAndValidateJson(content);
        if (settings.enabled()) {
            boolean published = gateway.publishConfig(dataId, formatted, "json");
            if (!published) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "发布配置到 Nacos 失败，请检查 Nacos 状态");
            }
        } else {
            gateway.publishConfig(dataId, formatted, "json");
        }
        applyUpdate(dataId, formatted, gateway.sourceName());
        return buildEntry(spec);
    }

    @BizLog(
            type = "ADMIN_CONFIG",
            subType = "RESET",
            bizNo = "#dataId",
            success = "管理员将配置 {{#dataId}} 重置为默认值",
            fail = "重置配置 {{#dataId}} 失败：{{#errorMessage}}",
            sync = true)
    public ConfigEntry reset(String dataId) {
        ConfigSpec spec = requireSpec(dataId);
        String defaultJson = toPrettyJson(spec.defaults());
        if (settings.enabled()) {
            boolean published = gateway.publishConfig(dataId, defaultJson, "json");
            if (!published) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "重置 Nacos 配置失败，请检查 Nacos 状态");
            }
        } else {
            gateway.publishConfig(dataId, defaultJson, "json");
        }
        applyUpdate(dataId, defaultJson, gateway.sourceName());
        return buildEntry(spec);
    }

    public boolean registrationEnabled(boolean fallback) {
        return boolVal("registrationEnabled", fallback);
    }

    public boolean rateLimitEnabled(boolean fallback) {
        return boolVal("rateLimitEnabled", fallback);
    }

    public int rateLimitAuthPerMinute(int fallback) {
        return intVal("rateLimitAuthPerMinute", fallback);
    }

    public int rateLimitRedeemPerMinute(int fallback) {
        return intVal("rateLimitRedeemPerMinute", fallback);
    }

    public int rateLimitGenerationPerMinute(int fallback) {
        return intVal("rateLimitGenerationPerMinute", fallback);
    }

    public int rateLimitUploadPerMinute(int fallback) {
        return intVal("rateLimitUploadPerMinute", fallback);
    }

    public long initialCreditUnits(long fallback) {
        return longVal("initialCreditUnits", fallback);
    }

    public double contextCompactThreshold(double fallback) {
        return doubleVal("contextCompactThreshold", fallback);
    }

    public double contextCompactKeepRatio(double fallback) {
        return doubleVal("contextCompactKeepRatio", fallback);
    }

    public int contextSummaryMaxOutputTokens(int fallback) {
        return intVal("contextSummaryMaxOutputTokens", fallback);
    }

    public long streamTimeoutMs(long fallback) {
        return longVal("streamTimeoutMs", fallback);
    }

    public boolean alertsEnabled(boolean fallback) {
        return boolVal("alertsEnabled", fallback);
    }

    public String smtpHost(String fallback) {
        return stringVal("smtpHost", fallback);
    }

    public int smtpPort(int fallback) {
        return intVal("smtpPort", fallback);
    }

    public String smtpUsername(String fallback) {
        return stringVal("smtpUsername", fallback);
    }

    public String smtpFrom(String fallback) {
        return stringVal("smtpFrom", fallback);
    }

    public boolean smtpSslEnabled(boolean fallback) {
        return boolVal("smtpSslEnabled", fallback);
    }

    public boolean webhookAllowPrivateNetwork(boolean fallback) {
        return boolVal("webhookAllowPrivateNetwork", fallback);
    }

    public Status statusSummary() {
        return new Status(
                settings.enabled(),
                gateway.isRemoteActive(),
                settings.serverAddr(),
                settings.namespace(),
                settings.group(),
                gateway.sourceName());
    }

    private void seedLocalDefaults() {
        for (ConfigSpec spec : SPECS) {
            String defaultJson = toPrettyJson(spec.defaults());
            rawConfigs.put(spec.dataId(), new StoredConfig(defaultJson, md5(defaultJson), "DEFAULT", clock.instant()));
        }
        rebuildMergedSnapshot();
    }

    void applyUpdate(String dataId, String content, String source) {
        try {
            String formatted = normalizeAndValidateJson(content);
            rawConfigs.put(dataId, new StoredConfig(formatted, md5(formatted), source, clock.instant()));
            rebuildMergedSnapshot();
            log.info("Dynamic config updated: dataId={}, source={}", dataId, source);
        } catch (RuntimeException exception) {
            log.warn("Ignoring invalid config update for dataId={}: {}", dataId, exception.getMessage());
        }
    }

    private synchronized void rebuildMergedSnapshot() {
        Map<String, Object> next = new LinkedHashMap<>();
        for (ConfigSpec spec : SPECS) {
            next.putAll(spec.defaults());
            StoredConfig stored = rawConfigs.get(spec.dataId());
            if (stored != null) {
                try {
                    Map<String, Object> parsed = objectMapper.readValue(
                            stored.content(), new TypeReference<LinkedHashMap<String, Object>>() {});
                    if (parsed != null) {
                        next.putAll(parsed);
                    }
                } catch (RuntimeException exception) {
                    log.warn("Failed parsing cached config {}", spec.dataId(), exception);
                }
            }
        }
        mergedValues.set(Map.copyOf(next));
    }

    private ConfigEntry buildEntry(ConfigSpec spec) {
        StoredConfig stored = rawConfigs.get(spec.dataId());
        String content = stored == null ? toPrettyJson(spec.defaults()) : stored.content();
        String md5 = stored == null ? md5(content) : stored.md5();
        String source = stored == null ? gateway.sourceName() : stored.source();
        Instant updatedAt = stored == null ? clock.instant() : stored.updatedAt();
        return new ConfigEntry(
                spec.dataId(),
                settings.group(),
                settings.namespace(),
                spec.description(),
                content,
                toPrettyJson(spec.defaults()),
                md5,
                source,
                settings.enabled(),
                gateway.isRemoteActive(),
                updatedAt);
    }

    private ConfigSpec requireSpec(String dataId) {
        for (ConfigSpec spec : SPECS) {
            if (spec.dataId().equals(dataId)) {
                return spec;
            }
        }
        throw new BusinessException(CommonErrorCode.NOT_FOUND, "未找到受管配置项: " + dataId);
    }

    private String normalizeAndValidateJson(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "配置内容不能为空");
        }
        try {
            JsonNode tree = objectMapper.readTree(content);
            if (tree == null || !tree.isObject()) {
                throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "配置内容必须是 JSON 对象");
            }
            return objectMapper.writeValueAsString(tree);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "配置内容不是合法的 JSON: " + exception.getMessage());
        }
    }

    private String toPrettyJson(Map<String, Object> value) {
        return objectMapper.writeValueAsString(value);
    }

    private boolean boolVal(String key, boolean fallback) {
        Object val = mergedValues.get().get(key);
        if (val instanceof Boolean bool) {
            return bool;
        }
        if (val instanceof String str && !str.isBlank()) {
            return Boolean.parseBoolean(str.trim());
        }
        return fallback;
    }

    private int intVal(String key, int fallback) {
        Object val = mergedValues.get().get(key);
        if (val instanceof Number number) {
            return number.intValue();
        }
        if (val instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private long longVal(String key, long fallback) {
        Object val = mergedValues.get().get(key);
        if (val instanceof Number number) {
            return number.longValue();
        }
        if (val instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private double doubleVal(String key, double fallback) {
        Object val = mergedValues.get().get(key);
        if (val instanceof Number number) {
            return number.doubleValue();
        }
        if (val instanceof String str && !str.isBlank()) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String stringVal(String key, String fallback) {
        Object val = mergedValues.get().get(key);
        if (val == null) {
            return fallback;
        }
        return val.toString();
    }

    private static String md5(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }

    private record ConfigSpec(String dataId, String description, Map<String, Object> defaults) {
    }

    private record StoredConfig(String content, String md5, String source, Instant updatedAt) {
    }

    public record ConfigEntry(
            String dataId,
            String group,
            String namespace,
            String description,
            String content,
            String defaultContent,
            String md5,
            String source,
            boolean nacosEnabled,
            boolean nacosConnected,
            Instant updatedAt) {
    }

    public record Status(
            boolean enabled,
            boolean connected,
            String serverAddr,
            String namespace,
            String group,
            String source) {
    }
}
