package app.pagefault.visionforge.common.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Holds hot-reloaded business runtime configuration backed by PostgreSQL.
 *
 * <p>Each API or worker instance keeps an in-process snapshot. A lightweight scheduled refresh
 * compares row versions so configuration published by the admin console reaches every instance
 * without an external config center.</p>
 */
@Slf4j
public class DynamicRuntimeConfig {

    public static final String DATA_ID_RUNTIME = "visionforge-runtime.json";
    public static final String DATA_ID_SECURITY = "visionforge-security.json";
    public static final String DATA_ID_BILLING = "visionforge-billing.json";
    public static final String DATA_ID_ALERT = "visionforge-alert.json";

    private static final String SOURCE = "DATABASE";

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

    private final ConfigStore store;
    private final long refreshIntervalMs;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Map<String, StoredConfig> rawConfigs = new ConcurrentHashMap<>();
    private final AtomicReference<Map<String, Object>> mergedValues = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, Long>> versions = new AtomicReference<>(Map.of());
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private volatile ScheduledExecutorService scheduler;

    public DynamicRuntimeConfig(ConfigStore store, long refreshIntervalMs, Clock clock) {
        this.store = store;
        this.refreshIntervalMs = refreshIntervalMs;
        this.clock = clock;
        this.objectMapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        seedLocalDefaults();
    }

    public void init() {
        for (ConfigSpec spec : SPECS) {
            store.ensure(spec.dataId(), toPrettyJson(spec.defaults()));
        }
        reloadAll();
        if (refreshIntervalMs > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dynamic-config-refresh");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(
                    this::refreshIfChanged,
                    refreshIntervalMs,
                    refreshIntervalMs,
                    TimeUnit.MILLISECONDS);
        }
    }

    public void close() {
        ScheduledExecutorService current = scheduler;
        if (current != null) {
            current.shutdownNow();
        }
    }

    public List<ConfigEntry> listEntries() {
        refreshIfChanged();
        List<ConfigEntry> entries = new ArrayList<>(SPECS.size());
        for (ConfigSpec spec : SPECS) {
            entries.add(buildEntry(spec));
        }
        return entries;
    }

    public ConfigEntry getEntry(String dataId) {
        ConfigSpec spec = requireSpec(dataId);
        refreshIfChanged();
        return buildEntry(spec);
    }

    @BizLog(
            type = "ADMIN_CONFIG",
            subType = "PUBLISH",
            bizNo = "#dataId",
            success = "管理员发布系统参数 {{#dataId}}",
            fail = "发布系统参数 {{#dataId}} 失败：{{#errorMessage}}",
            sync = true)
    public ConfigEntry publish(UUID updatedBy, String dataId, String content) {
        ConfigSpec spec = requireSpec(dataId);
        String formatted = normalizeAndValidateJson(content);
        StoredConfig saved = save(dataId, formatted, updatedBy);
        applyUpdate(dataId, saved);
        return buildEntry(spec);
    }

    @BizLog(
            type = "ADMIN_CONFIG",
            subType = "RESET",
            bizNo = "#dataId",
            success = "管理员将系统参数 {{#dataId}} 重置为默认值",
            fail = "重置系统参数 {{#dataId}} 失败：{{#errorMessage}}",
            sync = true)
    public ConfigEntry reset(UUID updatedBy, String dataId) {
        ConfigSpec spec = requireSpec(dataId);
        StoredConfig saved = save(dataId, toPrettyJson(spec.defaults()), updatedBy);
        applyUpdate(dataId, saved);
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
        refreshIfChanged();
        return new Status(true, connected.get(), SOURCE);
    }

    private StoredConfig save(String dataId, String content, UUID updatedBy) {
        try {
            ConfigStore.StoredConfig persisted = store.save(dataId, content, updatedBy);
            StoredConfig saved = new StoredConfig(
                    persisted.content(),
                    md5(persisted.content()),
                    SOURCE,
                    persisted.version(),
                    persisted.updatedAt());
            Map<String, Long> next = new HashMap<>(versions.get());
            next.put(dataId, saved.version());
            versions.set(Map.copyOf(next));
            return saved;
        } catch (RuntimeException exception) {
            log.error("Failed to persist dynamic config dataId={}", dataId, exception);
            throw new BusinessException(
                    CommonErrorCode.SERVICE_UNAVAILABLE,
                    "保存系统参数失败，请稍后重试");
        }
    }

    private void refreshIfChanged() {
        try {
            Map<String, Long> current = store.versions();
            if (!current.equals(versions.get())) {
                reloadAll();
            }
            connected.set(true);
        } catch (RuntimeException exception) {
            connected.set(false);
            log.warn("Failed to refresh dynamic config versions: {}", exception.getMessage());
        }
    }

    private synchronized void reloadAll() {
        Map<String, ConfigStore.StoredConfig> rows = store.loadAll();
        Map<String, Long> nextVersions = new HashMap<>();
        rows.forEach((dataId, row) -> nextVersions.put(dataId, row.version()));
        for (ConfigSpec spec : SPECS) {
            ConfigStore.StoredConfig row = rows.get(spec.dataId());
            if (row == null) {
                applyUpdate(spec.dataId(), new StoredConfig(
                        toPrettyJson(spec.defaults()), md5(toPrettyJson(spec.defaults())),
                        SOURCE, 0, clock.instant()));
                nextVersions.put(spec.dataId(), 0L);
            } else {
                applyUpdate(spec.dataId(), new StoredConfig(
                        row.content(), md5(row.content()), SOURCE, row.version(), row.updatedAt()));
            }
        }
        versions.set(Map.copyOf(nextVersions));
        connected.set(true);
    }

    private void seedLocalDefaults() {
        for (ConfigSpec spec : SPECS) {
            String defaultJson = toPrettyJson(spec.defaults());
            rawConfigs.put(spec.dataId(), new StoredConfig(
                    defaultJson, md5(defaultJson), SOURCE, 0, clock.instant()));
        }
        rebuildMergedSnapshot();
    }

    private void applyUpdate(String dataId, StoredConfig stored) {
        try {
            String formatted = normalizeAndValidateJson(stored.content());
            rawConfigs.put(dataId, new StoredConfig(
                    formatted, md5(formatted), stored.source(), stored.version(), stored.updatedAt()));
            rebuildMergedSnapshot();
            log.info("Dynamic config updated: dataId={}, source={}, version={}",
                    dataId, stored.source(), stored.version());
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
        String source = stored == null ? SOURCE : stored.source();
        long version = stored == null ? 0 : stored.version();
        Instant updatedAt = stored == null ? clock.instant() : stored.updatedAt();
        return new ConfigEntry(
                spec.dataId(),
                spec.description(),
                content,
                toPrettyJson(spec.defaults()),
                md5,
                source,
                version,
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

    private record StoredConfig(
            String content, String md5, String source, long version, Instant updatedAt) {
    }

    public record ConfigEntry(
            String dataId,
            String description,
            String content,
            String defaultContent,
            String md5,
            String source,
            long version,
            Instant updatedAt) {
    }

    public record Status(boolean enabled, boolean connected, String source) {
    }
}
