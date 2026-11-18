package app.pagefault.visionforge.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.ToIntFunction;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.error.CommonErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies table-driven sliding-window rate limits to sensitive API endpoints.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final List<RateLimitRule> RULES = List.of(
            new RateLimitRule(
                    "auth",
                    List.of(
                            "/api/v1/auth/login",
                            "/api/v1/auth/register",
                            "/api/v1/auth/refresh"),
                    true,
                    RateLimitProperties::getAuthPerMinute),
            new RateLimitRule(
                    "redeem",
                    List.of("/api/v1/account/credit/redeem"),
                    false,
                    RateLimitProperties::getRedeemPerMinute),
            new RateLimitRule(
                    "generation",
                    List.of(
                            "/api/v1/conversations/*/messages",
                            "/api/v1/conversations/*/messages/**",
                            "/api/v1/generation-tasks",
                            "/api/v1/composition-tasks"),
                    false,
                    RateLimitProperties::getGenerationPerMinute),
            new RateLimitRule(
                    "upload",
                    List.of("/api/v1/conversations/*/uploads"),
                    false,
                    RateLimitProperties::getUploadPerMinute));

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final SlidingWindowRateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            SlidingWindowRateLimiter rateLimiter,
            RateLimitProperties properties,
            ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isEnabled() || !HttpMethod.POST.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = resolvePath(request);
        RateLimitRule matchedRule = matchRule(path);
        if (matchedRule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        int limit = matchedRule.limitExtractor().applyAsInt(properties);
        String subject = matchedRule.byIpOnly()
                ? "ip:" + resolveClientIp(request)
                : resolveSubject(request);
        SlidingWindowRateLimiter.Decision decision = rateLimiter.tryAcquire(matchedRule.bucket(), subject, limit);
        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));

        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            response.setStatus(CommonErrorCode.RATE_LIMITED.httpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiResponse.error(CommonErrorCode.RATE_LIMITED));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private RateLimitRule matchRule(String path) {
        for (RateLimitRule rule : RULES) {
            for (String pattern : rule.patterns()) {
                if (pathMatcher.match(pattern, path)) {
                    return rule;
                }
            }
        }
        return null;
    }

    private String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private String resolveSubject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getName() != null
                && !"anonymousUser".equals(authentication.getName())) {
            return "user:" + authentication.getName();
        }
        return "ip:" + resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (properties.isTrustForwardedHeaders()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int commaIndex = forwarded.indexOf(',');
                return (commaIndex >= 0 ? forwarded.substring(0, commaIndex) : forwarded).trim();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private record RateLimitRule(
            String bucket,
            List<String> patterns,
            boolean byIpOnly,
            ToIntFunction<RateLimitProperties> limitExtractor) {
    }
}
