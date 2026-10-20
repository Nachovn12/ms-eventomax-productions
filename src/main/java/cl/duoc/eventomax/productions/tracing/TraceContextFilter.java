package cl.duoc.eventomax.productions.tracing;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TraceContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var previous = MDC.getCopyOfContextMap();
        try {
            String trace = TraceContext.normalize(request.getHeader("X-Trace-Id"));
            String correlation = TraceContext.normalize(request.getHeader("X-Correlation-Id"));
            MDC.put("traceId", trace);
            MDC.put("correlationId", correlation);
            response.setHeader("X-Trace-Id", trace);
            response.setHeader("X-Correlation-Id", correlation);
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
            if (previous != null) MDC.setContextMap(previous);
        }
    }
}
