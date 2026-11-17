package app.pagefault.visionforge.common.audit;

import java.util.UUID;

@FunctionalInterface
public interface TenantProvider {

    /**
     * @return the current workspace id, or {@code null} for system operations
     */
    UUID currentWorkspaceId();
}
