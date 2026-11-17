package app.pagefault.visionforge.api.security;

import java.util.UUID;

import org.springframework.stereotype.Component;

import app.pagefault.visionforge.common.audit.TenantProvider;

@Component
public class NoopTenantProvider implements TenantProvider {

    @Override
    public UUID currentWorkspaceId() {
        return null;
    }
}
