package app.pagefault.visionforge.api.security;

import java.util.UUID;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.common.audit.Operator;
import app.pagefault.visionforge.common.audit.OperatorProvider;

@Component
public class SecurityOperatorProvider implements OperatorProvider {

    @Override
    public Operator current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Operator.system();
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String email = jwt.getClaimAsString("email");
            return new Operator(
                    parseUserId(jwt.getSubject()),
                    email == null || email.isBlank() ? authentication.getName() : email);
        }
        return new Operator(null, authentication.getName());
    }

    private UUID parseUserId(String subject) {
        try {
            return subject == null ? null : UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
