# Contrato Kafka: productions.events (v1.0)
**Microservicio Emisor:** `ms-eventomax-productions`  
**Consumidores Principales:** `ms-eventomax-audit`, `ms-eventomax-report`  
**Versión del Esquema:** `1.0`  
**Tópico Kafka:** `productions.events`

---

## 1. Especificación General y Semántica

- **Protocolo y Serialización:** Apache Kafka, mensajes codificados en JSON (UTF-8).
- **Semántica de Entrega:** *At-least-once* eventual. El emisor implementa el patrón *Transactional Outbox* para garantizar persistencia atómica local (la entidad y el evento se confirman o revierten juntos) y entrega eventual al broker mediante sondeos programados con intervalo fijo (`fixedDelay`), reintentos programados acotados (máximo 3 intentos antes de transicionar a `ERROR`) y recuperación administrativa; el mecanismo no asegura entrega absoluta ante fallos permanentes o estados terminales no recuperados.
- **Idempotencia:** Los consumidores **deben** deduplicar eventos utilizando el campo `eventId` (UUID v4) en sus respectivos modelos de lectura/auditoría (`processed_event`).
- **Clave de Particionamiento (Partition Key):** `String.valueOf(payload.productionId)`. Garantiza que todos los eventos asociados a una misma producción se enruten a la misma partición física de Kafka, preservando el orden FIFO a nivel de partición. Cabe destacar que la secuenciación causal por producción no depende únicamente de Kafka: se apoya en tres capas acopladas: (1) el orden de registro en base de datos (`(created_at, sequence_no)`), (2) el despacho serializado por agregado del Outbox Relay (que bloquea eventos posteriores ante predecesores en `PENDING`, `IN_PROGRESS` o `ERROR`), y (3) la confirmación síncrona de recepción (ACK del broker Kafka) antes de marcar el evento como `SENT`.
- **Formato Temporal:** ISO-8601 en tiempo universal coordinado (UTC), representación estándar Java `Instant` (`YYYY-MM-DDTHH:mm:ss.sssZ`).

---

## 2. Estructura del Sobre Estándar (`KafkaEventEnvelope`)

Todo mensaje publicado en `productions.events` utiliza el envelope estándar de EventoMax:

| Campo | Tipo | Obligatorio | Descripción |
| :--- | :--- | :---: | :--- |
| `schemaVersion` | `String` | Sí | Versión del contrato del sobre. Valor fijo: `"1.0"`. |
| `eventId` | `UUID` | Sí | Identificador único y global del evento (UUID v4). Utilizado como clave de deduplicación. |
| `type` | `String` | Sí | Discriminador del tipo de evento (`"ProductionCreated"` o `"ProductionStatusChanged"`). |
| `timestamp` | `Instant` | Sí | Marca temporal de emisión del evento en UTC. |
| `traceId` | `String` | No | Identificador de trazabilidad distribuida propagado vía MDC / HTTP. |
| `correlationId`| `String` | No | Identificador de correlación del flujo de negocio. |
| `actor` | `String` | Sí | Identificador de sujeto obtenido de la aserción JWT (`jwt.getSubject()`). Corresponde a una cadena opaca definida por el proveedor de identidad (claim `sub`); no garantiza formato de correo electrónico ni necesariamente estructura de UUID. Validado en Audit como no vacío. |
| `payload` | `Object` | Sí | Objeto con los datos de dominio específicos del evento. |

---

## 3. Especificación de Eventos y Payloads

### 3.1. Evento `ProductionCreated`
Emitido atómicamente al registrarse una nueva producción en estado inicial `SOLICITADO`.

#### Campos del Payload
| Campo | Tipo | Obligatorio | Regla de Validación / Descripción |
| :--- | :--- | :---: | :--- |
| `productionId` | `Long` | Sí | Identificador numérico autoincremental de la producción ($> 0$). |
| `organizerId` | `String` | Sí | Identificador del organizador del evento. |
| `productionName` | `String` | Sí | Nombre de la producción (no blanco). |
| `status` | `String` | Sí | Estado inicial, estrictamente `"SOLICITADO"`. |
| `scheduledAt` | `String` | Sí | Fecha y hora programada del evento (`LocalDateTime` ISO-8601). |
| `location` | `String` | Sí | Ubicación o recinto del evento. |
| `occurredAt` | `Instant`| Sí | Momento exacto de creación en base de datos (UTC ISO-8601). |

#### Ejemplo JSON Sanitizado (`ProductionCreated`)
```json
{
  "schemaVersion": "1.0",
  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "type": "ProductionCreated",
  "timestamp": "2026-10-12T18:30:00.120Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "00f067aa0ba902b7",
  "actor": "usr-sub-org-91a2b3c4",
  "payload": {
    "productionId": 105,
    "organizerId": "org-duoc-01",
    "productionName": "Concierto Primavera 2026",
    "status": "SOLICITADO",
    "scheduledAt": "2026-11-15T20:00:00",
    "location": "Teatro Caupolicán",
    "occurredAt": "2026-10-12T18:30:00.115Z"
  }
}
```

---

### 3.2. Evento `ProductionStatusChanged`
Emitido atómicamente cuando una producción realiza una transición de estado válida dentro del ciclo:  
`SOLICITADO → CONFIRMADO → EN_MONTAJE → EN_EJECUCION → CERRADO` (o hacia `CANCELADO`).

#### Campos del Payload
| Campo | Tipo | Obligatorio | Regla de Validación / Descripción |
| :--- | :--- | :---: | :--- |
| `productionId` | `Long` | Sí | Identificador numérico de la producción ($> 0$). |
| `organizerId` | `String` | Sí | Identificador del organizador. |
| `productionName` | `String` | Sí | Nombre de la producción. |
| `previousStatus` | `String` | Sí | Estado previo. Uno de: `SOLICITADO`, `CONFIRMADO`, `EN_MONTAJE`, `EN_EJECUCION`, `CERRADO`, `CANCELADO`. |
| `newStatus` | `String` | Sí | Nuevo estado alcanzado tras la transición. |
| `scheduledAt` | `String` | Sí | Fecha y hora programada del evento. |
| `location` | `String` | Sí | Ubicación o recinto del evento. |
| `occurredAt` | `Instant`| Sí | Momento de la transición en UTC ISO-8601. Utilizado por Report para filtrar por ventana temporal (`last24h`, `last7d`). |

#### Ejemplo JSON Sanitizado (`ProductionStatusChanged`)
```json
{
  "schemaVersion": "1.0",
  "eventId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "type": "ProductionStatusChanged",
  "timestamp": "2026-10-12T18:35:10.500Z",
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "correlationId": "c0a80101-8b84-4f99-939c-05bfd73c622a",
  "actor": "usr-sub-adm-4d5e6f7a",
  "payload": {
    "productionId": 105,
    "organizerId": "org-duoc-01",
    "productionName": "Concierto Primavera 2026",
    "previousStatus": "SOLICITADO",
    "newStatus": "CONFIRMADO",
    "scheduledAt": "2026-11-15T20:00:00",
    "location": "Teatro Caupolicán",
    "occurredAt": "2026-10-12T18:35:10.490Z"
  }
}
```

---

## 4. Compatibilidad con Consumidores del Ecosistema

### 4.1. `ms-eventomax-audit` (develop)
- **Consumo:** Procesa tanto `ProductionCreated` como `ProductionStatusChanged`.
- **Validaciones:** Comprueba `schemaVersion == "1.0"`, `eventId`, `timestamp`, `actor != null && !isBlank()`, `occurredAt` (UTC) y validez de estados.
- **Persistencia:** Almacena en tabla `audit_event` e inserta `eventId` en `processed_event` para idempotencia estricta.

### 4.2. `ms-eventomax-report` (feature/EMX-68-report-service)
- **Consumo:** Procesa selectivamente `ProductionStatusChanged`.
- **Tolerancia a otros eventos:** El mapper ignora con `log.info` eventos cuyo `type` no sea `ProductionStatusChanged` (como `ProductionCreated`), evitando fallos en la cola.
- **Persistencia:** Registra `eventId` en tabla `processed_event` e inserta los hechos de transición en `report_production_fact`.
- **KPIs:** Alimenta los indicadores agregados calculados en `GET /api/report/kpis?range=last24h` y `GET /api/report/kpis?range=last7d`.

