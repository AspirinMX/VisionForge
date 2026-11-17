package app.pagefault.visionforge.api.web;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import app.pagefault.visionforge.common.logging.TraceContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Ensures every request has a correlation id that is logged and returned to the client.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = TraceContext.ensureTraceId(request.getHeader(TraceContext.REQUEST_ID_HEADER));
        response.setHeader(TraceContext.REQUEST_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
