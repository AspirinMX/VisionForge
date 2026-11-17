package app.pagefault.visionforge.api.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.common.logging.LogMasker;
import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.workspace.WorkspaceService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final IdentityService identityService;
    private final WorkspaceService workspaceService;
    private final String email;
    private final String password;
    private final String displayName;

    public BootstrapAdminInitializer(
            IdentityService identityService,
            WorkspaceService workspaceService,
            @Value("${visionforge.identity.bootstrap-admin.email:}") String email,
            @Value("${visionforge.identity.bootstrap-admin.password:}") String password,
            @Value("${visionforge.identity.bootstrap-admin.display-name:Administrator}") String displayName) {
        this.identityService = identityService;
        this.workspaceService = workspaceService;
        this.email = email;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.info("Bootstrap admin is not configured; skipping");
            return;
        }
        UserAccount admin = identityService.ensureAdmin(email, password, displayName);
        workspaceService.ensurePersonalWorkspace(admin.getId());
        log.info("Bootstrap admin ensured: {}", LogMasker.email(admin.getEmail()));
    }
}
