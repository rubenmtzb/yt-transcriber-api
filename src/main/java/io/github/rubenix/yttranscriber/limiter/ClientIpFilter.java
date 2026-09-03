package io.github.rubenix.yttranscriber.limiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the network identity used to enforce the abuse limits that actually have to hold.
 *
 * <p>{@link SessionIdFilter}'s id cannot carry that weight: it is whatever the caller put in a
 * header or query parameter, so anyone who changes it is handed a fresh budget. That is fine for
 * what the session id is for -- telling one browser tab's usage from another's so the UI can show
 * a person their own remaining allowance -- but it means the session id is a convenience, not a
 * boundary. The boundary is the address the request actually came from.
 *
 * <p>Picking that address behind two proxies is the whole difficulty, because the obvious source is
 * forgeable. {@code X-Forwarded-For} is a list that each hop <em>appends</em> to, so a caller can
 * seed it with an address of their choosing and that value stays leftmost -- exactly where the
 * conventional "first entry is the client" reading looks. Spring's own {@code ForwardedHeaderFilter}
 * (enabled here via {@code server.forward-headers-strategy}) reads it that way, so
 * {@code getRemoteAddr()} inherits the same weakness and cannot be trusted on its own either.
 *
 * <p>Hence the order below. {@code CF-Connecting-IP} is set by Cloudflare, which fronts this
 * deployment, and Cloudflare <em>overwrites</em> it rather than appending -- a value the caller sent
 * is discarded, so it is the one header here that a caller cannot influence. The remaining sources
 * are fallbacks for running somewhere else (a plain container, local development), and are only as
 * trustworthy as the environment: if this ever moves off a platform that normalises the header, this
 * order is the thing to revisit.
 */
@Component
public class ClientIpFilter extends OncePerRequestFilter {

    /** Cloudflare's own header. Overwritten at their edge, so a caller-supplied value never survives. */
    static final String CLOUDFLARE_HEADER = "CF-Connecting-IP";
    static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    public static final String REQUEST_ATTRIBUTE = "clientIp";
    public static final String MDC_KEY = "clientIp";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = resolveClientIp(request);
        request.setAttribute(REQUEST_ATTRIBUTE, clientIp);
        // Logged on every line so an abuse report can be traced back to an address, which the
        // privacy page already tells people happens. Nothing else is recorded about the caller.
        MDC.put(MDC_KEY, clientIp);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolveClientIp(HttpServletRequest request) {
        String cloudflare = request.getHeader(CLOUDFLARE_HEADER);
        if (cloudflare != null && !cloudflare.isBlank()) {
            return cloudflare.trim();
        }

        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // Leftmost entry is the conventional "original client" reading. Forgeable where nothing
            // normalises the header, which is precisely why it sits below Cloudflare's.
            String first = forwardedFor.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }

        String remoteAddress = request.getRemoteAddr();
        // Never null in a servlet container in practice, but a null key would blow up the limiter's
        // ConcurrentHashMap rather than merely mis-attributing one request.
        return remoteAddress != null ? remoteAddress : "unknown";
    }
}
