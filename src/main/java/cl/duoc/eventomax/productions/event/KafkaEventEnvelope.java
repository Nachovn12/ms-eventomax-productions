package cl.duoc.eventomax.productions.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

public class KafkaEventEnvelope<T> {

    private String schemaVersion = "1.0";
    private UUID eventId;
    private String type;
    private Instant timestamp;
    private String traceId;
    private String correlationId;
    private String actor;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String actorDisplayName;
    private String source = "ms-eventomax-productions";
    private T payload;

    public KafkaEventEnvelope() {
    }

    public KafkaEventEnvelope(UUID eventId, String type, Instant timestamp, String traceId, String correlationId, String actor, T payload) {
        this(eventId, type, timestamp, traceId, correlationId, actor, null, "ms-eventomax-productions", payload);
    }

    public KafkaEventEnvelope(UUID eventId, String type, Instant timestamp, String traceId, String correlationId, String actor, String source, T payload) {
        this(eventId, type, timestamp, traceId, correlationId, actor, null, source, payload);
    }

    public KafkaEventEnvelope(UUID eventId, String type, Instant timestamp, String traceId, String correlationId, String actor, String actorDisplayName, String source, T payload) {
        this.eventId = eventId;
        this.type = type;
        this.timestamp = timestamp;
        this.traceId = traceId;
        this.correlationId = correlationId;
        this.actor = actor;
        this.actorDisplayName = normalizeActorDisplayName(actorDisplayName);
        this.source = (source != null && !source.isBlank()) ? source : "ms-eventomax-productions";
        this.payload = payload;
    }

    private static String normalizeActorDisplayName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 150 ? trimmed.substring(0, 150) : trimmed;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getActorDisplayName() {
        return actorDisplayName;
    }

    public void setActorDisplayName(String actorDisplayName) {
        this.actorDisplayName = normalizeActorDisplayName(actorDisplayName);
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public T getPayload() {
        return payload;
    }

    public void setPayload(T payload) {
        this.payload = payload;
    }
}

