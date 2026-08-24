package io.github.rubenix.yttranscriber.limiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Resolves the anonymous session identity used to apply per-session usage limits, mirroring
 * {@link io.github.rubenix.yttranscriber.config.RequestIdFilter}'s echo-a-header pattern: reuses
 * an inbound {@value #HEADER_NAME} header when present, otherwise issues a fresh id and echoes it
 * back for the client to reuse on subsequent requests.
 *
 * <p>Deliberately not a cookie: the frontend (:4321) and backend (:8080) are different ports on
 * localhost, which Chromium treats as different *sites* (no registrable domain to strip the port
 * from), so neither SameSite=Lax nor SameSite=None survive that hop in local dev -- Lax is dropped
 * because the request genuinely is cross-site, and None requires Secure, which browsers refuse to
 * send back over plain http://localhost. Confirmed empirically with a real cross-origin fetch
 * before choosing this approach. A JS-readable header is an acceptable trade for an anonymous,
 * non-security-critical rate-limit identity.
 *
 * <p>Also accepts the id via a {@value #QUERY_PARAM_NAME} query parameter, falling back to it when
 * the header is absent: the streaming endpoint is opened with the browser's native
 * {@code EventSource}, which cannot set custom request headers at all, so that path has no way to
 * send {@value #HEADER_NAME} even though the regular fetch-based endpoint does.
 */
@Component
public class SessionIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Session-Id";
    public static final String QUERY_PARAM_NAME = "sessionId";
    public static final String REQUEST_ATTRIBUTE = "sessionId";
    public static final String MDC_KEY = "sessionId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String sessionId = resolveSessionId(request);
        request.setAttribute(REQUEST_ATTRIBUTE, sessionId);
        MDC.put(MDC_KEY, sessionId);
        response.setHeader(HEADER_NAME, sessionId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveSessionId(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        if (header != null && !header.isBlank()) {
            return header;
        }
        String queryParam = request.getParameter(QUERY_PARAM_NAME);
        if (queryParam != null && !queryParam.isBlank()) {
            return queryParam;
        }
        return UUID.randomUUID().toString();
    }
}
