package app.pagefault.visionforge.agent.provider.media;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.common.error.BusinessException;

public class MediaProviderRegistry {

    private final Map<MediaProtocol, MediaProvider> providers = new EnumMap<>(MediaProtocol.class);

    public MediaProviderRegistry(List<MediaProvider> providers) {
        for (MediaProvider provider : providers) {
            MediaProvider existing = this.providers.put(provider.protocol(), provider);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate media provider for protocol " + provider.protocol());
            }
        }
    }

    public MediaProvider require(MediaProtocol protocol) {
        MediaProvider provider = protocol == null ? null : providers.get(protocol);
        if (provider == null) {
            throw new BusinessException(
                    AgentErrorCode.GENERATION_PROVIDER_UNSUPPORTED,
                    "尚未实现媒体协议 " + protocol);
        }
        return provider;
    }
}
