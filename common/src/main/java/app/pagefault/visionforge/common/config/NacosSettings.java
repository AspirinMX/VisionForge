package app.pagefault.visionforge.common.config;

public record NacosSettings(
        boolean enabled,
        String serverAddr,
        String namespace,
        String group,
        String username,
        String password,
        long timeoutMs) {

    public NacosSettings {
        serverAddr = (serverAddr == null || serverAddr.isBlank()) ? "127.0.0.1:8848" : serverAddr.trim();
        namespace = (namespace == null || namespace.isBlank()) ? "public" : namespace.trim();
        group = (group == null || group.isBlank()) ? "VISIONFORGE_GROUP" : group.trim();
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
        timeoutMs = timeoutMs <= 0 ? 5000L : timeoutMs;
    }
}
