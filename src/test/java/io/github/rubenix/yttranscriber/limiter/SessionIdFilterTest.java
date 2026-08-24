package io.github.rubenix.yttranscriber.limiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionIdFilterTest {

    private final SessionIdFilter filter = new SessionIdFilter();

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain chain;

    @Test
    void reusesTheHeaderWhenPresent() throws Exception {
        when(request.getHeader(SessionIdFilter.HEADER_NAME)).thenReturn("from-header");

        filter.doFilter(request, response, chain);

        verify(request).setAttribute(SessionIdFilter.REQUEST_ATTRIBUTE, "from-header");
        verify(response).setHeader(SessionIdFilter.HEADER_NAME, "from-header");
    }

    @Test
    void fallsBackToTheQueryParamWhenTheHeaderIsAbsent() throws Exception {
        // EventSource (used by the SSE streaming endpoint) cannot set custom request headers at
        // all, so that path has no way to send X-Session-Id -- only the query param.
        when(request.getHeader(SessionIdFilter.HEADER_NAME)).thenReturn(null);
        when(request.getParameter(SessionIdFilter.QUERY_PARAM_NAME)).thenReturn("from-query-param");

        filter.doFilter(request, response, chain);

        verify(request).setAttribute(SessionIdFilter.REQUEST_ATTRIBUTE, "from-query-param");
    }

    @Test
    void prefersTheHeaderOverTheQueryParamWhenBothArePresent() throws Exception {
        when(request.getHeader(SessionIdFilter.HEADER_NAME)).thenReturn("from-header");

        filter.doFilter(request, response, chain);

        verify(request).setAttribute(SessionIdFilter.REQUEST_ATTRIBUTE, "from-header");
        verify(request, org.mockito.Mockito.never()).getParameter(SessionIdFilter.QUERY_PARAM_NAME);
    }

    @Test
    void generatesAFreshIdWhenNeitherHeaderNorQueryParamArePresent() throws Exception {
        when(request.getHeader(SessionIdFilter.HEADER_NAME)).thenReturn(null);
        when(request.getParameter(SessionIdFilter.QUERY_PARAM_NAME)).thenReturn(null);

        filter.doFilter(request, response, chain);

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(request).setAttribute(eq(SessionIdFilter.REQUEST_ATTRIBUTE), captor.capture());
        assertThat(captor.getValue()).isNotBlank();
    }
}
