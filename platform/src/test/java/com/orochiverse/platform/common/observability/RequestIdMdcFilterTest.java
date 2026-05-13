package com.orochiverse.platform.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdMdcFilterTest {

    private final RequestIdMdcFilter filter = new RequestIdMdcFilter();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void generates_a_request_id_when_none_supplied_and_echoes_it() throws Exception {
        var req = new MockHttpServletRequest("GET", "/anything");
        var res = new MockHttpServletResponse();
        var seen = new AtomicReference<String>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            seen.set(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID));
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilter(req, res, chain);

        assertThat(seen.get()).isNotNull().hasSize(16);
        assertThat(res.getHeader(RequestIdMdcFilter.HEADER_REQUEST_ID)).isEqualTo(seen.get());
        // Cleared after the chain runs.
        assertThat(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    void honors_a_client_supplied_request_id_for_distributed_traces() throws Exception {
        var req = new MockHttpServletRequest("GET", "/anything");
        req.addHeader(RequestIdMdcFilter.HEADER_REQUEST_ID, "trace-from-gateway");
        var res = new MockHttpServletResponse();
        var seen = new AtomicReference<String>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            seen.set(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID));
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilter(req, res, chain);

        assertThat(seen.get()).isEqualTo("trace-from-gateway");
        assertThat(res.getHeader(RequestIdMdcFilter.HEADER_REQUEST_ID)).isEqualTo("trace-from-gateway");
    }

    @Test
    void falls_back_to_a_fresh_id_for_blank_header() throws Exception {
        var req = new MockHttpServletRequest("GET", "/anything");
        req.addHeader(RequestIdMdcFilter.HEADER_REQUEST_ID, "   ");
        var res = new MockHttpServletResponse();
        var seen = new AtomicReference<String>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            seen.set(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID));
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilter(req, res, chain);

        assertThat(seen.get()).isNotBlank().hasSize(16);
    }
}
