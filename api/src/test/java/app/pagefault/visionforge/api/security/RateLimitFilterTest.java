package app.pagefault.visionforge.api.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import app.pagefault.visionforge.common.error.CommonErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void enforcesAuthRateLimitAndWritesUnifiedErrorEnvelope() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setAuthPerMinute(2);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
                (StringRedisTemplate) null,
                Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC),
                props);
        RateLimitFilter filter = new RateLimitFilter(limiter, props, objectMapper);

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            req.setRemoteAddr("198.51.100.10");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(res.getHeader("X-RateLimit-Limit")).isEqualTo("2");
        }

        MockHttpServletRequest blockedReq = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        blockedReq.setRemoteAddr("198.51.100.10");
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        filter.doFilter(blockedReq, blockedRes, new MockFilterChain());

        assertThat(blockedRes.getStatus()).isEqualTo(429);
        assertThat(blockedRes.getHeader("Retry-After")).isEqualTo("60");
        assertThat(blockedRes.getHeader("X-RateLimit-Remaining")).isEqualTo("0");

        JsonNode body = objectMapper.readTree(blockedRes.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo(CommonErrorCode.RATE_LIMITED.code());
        assertThat(body.get("message").asString()).isEqualTo(CommonErrorCode.RATE_LIMITED.message());
    }

    @Test
    void ignoresSpoofedForwardedForWhenTrustDisabledAndUsesAuthenticatedSubject() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setTrustForwardedHeaders(false);
        props.setAuthPerMinute(1);
        props.setRedeemPerMinute(1);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
                (StringRedisTemplate) null,
                Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC),
                props);
        RateLimitFilter filter = new RateLimitFilter(limiter, props, objectMapper);

        MockHttpServletRequest authFirst = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        authFirst.setRemoteAddr("203.0.113.5");
        authFirst.addHeader("X-Forwarded-For", "1.1.1.1");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(authFirst, res1, new MockFilterChain());
        assertThat(res1.getStatus()).isEqualTo(200);

        MockHttpServletRequest authSecondSpoofed = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        authSecondSpoofed.setRemoteAddr("203.0.113.5");
        authSecondSpoofed.addHeader("X-Forwarded-For", "8.8.8.8");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(authSecondSpoofed, res2, new MockFilterChain());
        assertThat(res2.getStatus()).isEqualTo(429);

        TestingAuthenticationToken auth = new TestingAuthenticationToken("user-42", "token");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest redeem1 = new MockHttpServletRequest("POST", "/api/v1/account/credit/redeem");
        redeem1.setRemoteAddr("203.0.113.5");
        MockHttpServletResponse redeemRes1 = new MockHttpServletResponse();
        filter.doFilter(redeem1, redeemRes1, new MockFilterChain());
        assertThat(redeemRes1.getStatus()).isEqualTo(200);

        MockHttpServletRequest redeem2 = new MockHttpServletRequest("POST", "/api/v1/account/credit/redeem");
        redeem2.setRemoteAddr("198.51.100.99");
        MockHttpServletResponse redeemRes2 = new MockHttpServletResponse();
        filter.doFilter(redeem2, redeemRes2, new MockFilterChain());
        assertThat(redeemRes2.getStatus()).isEqualTo(429);
    }

    @Test
    void bypassesGetRequests() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setAuthPerMinute(1);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
                (StringRedisTemplate) null,
                Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC),
                props);
        RateLimitFilter filter = new RateLimitFilter(limiter, props, objectMapper);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-RateLimit-Limit")).isNull();
    }
}
