package cl.duoc.eventomax.productions.tracing;

import java.util.UUID;
import org.slf4j.MDC;

public record TraceContext(String traceId, String correlationId) {
    public static String normalize(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
            ? value : UUID.randomUUID().toString();
    }
    public static TraceContext current() {
        return new TraceContext(normalize(MDC.get("traceId")), normalize(MDC.get("correlationId")));
    }
}
