package dev.tkachuk.openbanking.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private static final String REQUEST_ID = "99391c7e-ad88-49ec-a2ad-99ddcb1f7721";

    private final RequestIdFilter filter = new RequestIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> idDuringRequest = new AtomicReference<>();
    private final FilterChain chain = (req, res) -> idDuringRequest.set(MDC.get(RequestIdFilter.MDC_KEY));

    @Test
    void shouldLogUnderTheRequestIdAndEchoIt() throws Exception {
        filter.doFilter(request(REQUEST_ID), response, chain);

        assertThat(idDuringRequest).hasValue(REQUEST_ID);
        assertThat(response.getHeader("X-Request-ID")).isEqualTo(REQUEST_ID);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "99391c7e-ad88-49ec-a2ad-99ddcb1f7721\nfake log line"})
    void shouldIgnoreRequestIdThatIsNotUuid(String requestId) throws Exception {
        filter.doFilter(request(requestId), response, chain);

        assertThat(idDuringRequest).hasNullValue();
        assertThat(response.getHeader("X-Request-ID")).isNull();
    }

    @Test
    void shouldPassRequestWithoutRequestIdOn() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/v2/accounts"), response, chain);

        assertThat(idDuringRequest).hasNullValue();
        assertThat(response.getHeader("X-Request-ID")).isNull();
    }

    private static MockHttpServletRequest request(String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v2/accounts");
        request.addHeader("X-Request-ID", requestId);
        return request;
    }
}
