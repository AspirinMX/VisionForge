package app.pagefault.visionforge.agent.provider.media;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.volcengine.ark.runtime.service.ArkService;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

/**
 * Shares one Ark client per base URL/API key pair. Ark clients own a connection pool and an
 * executor, so creating one per request would leak resources under load.
 */
public class ArkClientFactory {

    private static final int MAX_CACHED_CLIENTS = 16;

    private final Map<String, ArkService> clients = new ConcurrentHashMap<>();
    private final String userAgent;

    public ArkClientFactory() {
        this("");
    }

    public ArkClientFactory(String userAgent) {
        this.userAgent = userAgent == null ? "" : userAgent.trim();
    }

    public Map<String, String> customHeaders() {
        return userAgent.isBlank() ? Map.of() : Map.of("User-Agent", userAgent);
    }

    public ArkService get(MediaProviderRequest request) {
        String baseUrl = requireText(request.baseUrl(), "Ark baseUrl 未配置");
        String apiKey = requireText(request.apiKey(), "Ark API Key 未配置");
        String key = baseUrl + '|' + apiKey;
        ArkService cached = clients.get(key);
        if (cached != null) {
            return cached;
        }
        ArkService created = ArkService.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .retryTimes(0)
                .timeout(Duration.ofMinutes(15))
                .build();
        if (clients.size() >= MAX_CACHED_CLIENTS) {
            clients.clear();
        }
        ArkService existing = clients.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(AgentErrorCode.GENERATION_PROVIDER_ERROR, message);
        }
        return value;
    }
}
