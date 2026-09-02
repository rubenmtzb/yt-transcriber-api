package io.github.rubenix.yttranscriber.limiter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of these is the precedence, not the parsing: every one of these headers can be set by
 * the caller, so which one wins decides whether the rate limit is a boundary or a suggestion.
 */
class ClientIpFilterTest {

    @Test
    void prefersCloudflaresHeaderOverEverythingElse() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientIpFilter.CLOUDFLARE_HEADER, "198.51.100.4");
        // What a caller trying to look like someone else would send. Cloudflare overwrites its own
        // header at the edge, so the forged chain below never gets a say.
        request.addHeader(ClientIpFilter.FORWARDED_FOR_HEADER, "1.2.3.4, 198.51.100.4");
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIpFilter.resolveClientIp(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void fallsBackToTheFirstForwardedForEntryWhenCloudflaresHeaderIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientIpFilter.FORWARDED_FOR_HEADER, "198.51.100.4, 10.0.0.9");
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientIpFilter.resolveClientIp(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void ignoresABlankCloudflareHeaderRatherThanKeyingEveryoneOnEmptyString() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientIpFilter.CLOUDFLARE_HEADER, "   ");
        request.addHeader(ClientIpFilter.FORWARDED_FOR_HEADER, "198.51.100.4");

        assertThat(ClientIpFilter.resolveClientIp(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void fallsBackToTheSocketAddressWhenNoProxyHeaderIsPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.55");

        assertThat(ClientIpFilter.resolveClientIp(request)).isEqualTo("192.0.2.55");
    }

    @Test
    void exposesTheResolvedAddressAsARequestAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientIpFilter.CLOUDFLARE_HEADER, "198.51.100.4");

        new ClientIpFilter().doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(),
                new org.springframework.mock.web.MockFilterChain());

        assertThat(request.getAttribute(ClientIpFilter.REQUEST_ATTRIBUTE)).isEqualTo("198.51.100.4");
    }
}
