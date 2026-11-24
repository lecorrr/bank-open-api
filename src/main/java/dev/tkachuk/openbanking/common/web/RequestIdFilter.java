package dev.tkachuk.openbanking.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts the provider's X-Request-ID into the logging MDC and echoes it in the response, as the contract asks. */
@Component
// Before Spring Security's filters (order -100), so refused requests echo the id too and their log lines carry it.
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    // Only a UUID in its usual form goes into the logs; the endpoints refuse a request without one.
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || !UUID_TEXT.matcher(requestId).matches()) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
