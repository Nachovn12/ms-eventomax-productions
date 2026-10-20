# ms-eventomax-productions — Microservicio de Dominio de Producciones y Orquestación Asíncrona (EP2)

**Asignatura:** Desarrollo Cloud Native I (`DSY1107`)
**Caso de Estudio:** Caso 8 — Plataforma de Producción y Montaje de Eventos (**EventoMax**)
**Equipo:** Ignacio Valeria, Benjamín Flores, Benjamín Espinoza

---

## 1. Propósito del Repositorio y Rol en EventoMax

`ms-eventomax-productions` es el microservicio central de escritura transaccional (*System of Record*) para el ciclo de vida de producciones de eventos, contratación de servicios de catálogo, coordinación de reservas de equipamiento e información de contacto minimizada del organizador.

En la arquitectura híbrida de **EventoMax EP2**, cumple un doble rol de mensajería desacoplada:
1. **Productor de Comandos Operativos hacia RabbitMQ (`cmd.direct`):** Dispara comandos punto-a-punto post-commit (`TransactionPhase.AFTER_COMMIT`) con **Publisher Confirms correlacionados** (`publisher-confirm-type: correlated`, `mandatory: true`) para:
   - Notificación por correo de cambios de estado (`email.send` → `q.cmd.email`).
   - Asignación de tickets de cuadrilla técnica al pasar a ejecución (`crew.ticket` → `q.cmd.crew`).
   - Generación del **Acta y Cotización Formal de Cierre en PDF** al cerrar la producción (`quote.gen` → `q.cmd.quote`), adjuntando un contrato enriquecido de 16 campos con servicios contratados, contacto del organizador enmascarado y línea de tiempo transaccional verificable.
2. **Productor de Eventos de Dominio hacia Apache Kafka (`productions.events`):** Implementa el patrón **Transactional Outbox** sobre PostgreSQL (`outbox_events` + `OutboxRelay`) para alimentar de manera asíncrona las proyecciones CQRS de `ms-eventomax-report` y la trazabilidad inmutable de `ms-eventomax-audit`.

---

## 2. Arquitectura del Módulo y Diagrama

```mermaid
flowchart LR
    subgraph Cliente y Borde
        SPA["Angular 22 SPA + MSAL"]
        APIGW["AWS API Gateway (JWT Authorizer)"]
        BFF["ms-eventomax-bff (:8081)"]
    end

    subgraph Dominio Producciones
        PROD["ms-eventomax-productions (:8082)"]
        PG_PROD[("PostgreSQL (V1..V7)\nproductions\nproduction_contracted_services\norganizer_contacts\noutbox_events\ninventory_reservation_tasks")]
        AFTER_COMMIT["Listeners AFTER_COMMIT\n(Email / Crew / Quote)"]
        PUB["RabbitMQCommandPublisher\n(Correlated Confirms + Returns)"]
        RELAY["OutboxRelay (@Scheduled)"]
    end

    subgraph Integraciones Síncronas y Brokers
        CAT["ms-eventomax-catalog (:8080)"]
        RMQ[["RabbitMQ Cluster\nExchange: cmd.direct\nDLX: cmd.dead.dlx"]]
        KFK[["Apache Kafka\nTopic: productions.events"]]
        NOTIFY["ms-eventomax-notify (:8083)\nOpenPDF + Tickets + SMTP"]
    end

    SPA -->|HTTPS + Bearer JWT| APIGW --> BFF --> PROD
    PROD <-->|JPA + Flyway| PG_PROD
    PROD -->|REST Reserva / Compensación| CAT
    PROD -->|AFTER_COMMIT| AFTER_COMMIT --> PUB
    PUB -->|email.send / crew.ticket / quote.gen| RMQ --> NOTIFY
    PG_PROD --> RELAY -->|KafkaEventEnvelope| KFK
```

---

## 3. Stack Tecnológico y Versiones Reales (`pom.xml`)

| Componente | Tecnología / Dependencia | Versión |
|---|---|---|
| Lenguaje | Java (Eclipse Temurin) | `25` |
| Framework Base | Spring Boot (`spring-boot-starter-parent`) | `4.1.1` |
| Persistencia | Spring Data JPA / Hibernate + PostgreSQL Driver | Boot `4.1.1` |
| Migraciones DB | Flyway Core + `flyway-database-postgresql` | Boot `4.1.1` (`V1` a `V7`) |
| Mensajería AMQP | `spring-boot-starter-amqp` (RabbitMQ) | Boot `4.1.1` |
| Streaming de Eventos | `spring-kafka` | Boot `4.1.1` |
| Seguridad | `spring-boot-starter-security` + `oauth2-resource-server` | Boot `4.1.1` |
| Observabilidad | `spring-boot-starter-actuator` + OpenAPI (`springdoc`) | `3.0.3` |
| Testing | JUnit 5, Mockito, Testcontainers (`2.0.5`), H2 | Boot `4.1.1` |

---

## 4. Cumplimiento de Rúbrica EP2 (Matriz Caso 8)

| Criterio de Evaluación EP2 | Implementación en `ms-eventomax-productions` | Estado |
|---|---|---|
| **Productor RabbitMQ (`RabbitTemplate`)** | `RabbitMQCommandPublisher` publica `MessageEnvelope<T>` serializado en JSON hacia `cmd.direct` con routing keys `email.send`, `crew.ticket` y `quote.gen`. | **CUMPLIDO** |
| **Confirmaciones y Resiliencia de Publicación** | `publisher-confirm-type: correlated`, `publisher-returns: true`, `template.mandatory: true`. Verificación explícita de `CorrelationData.getFuture()` (timeout 5 s) y `getReturned()`. | **CUMPLIDO** |
| **Desacoplamiento Transaccional** | Listeners `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` (`ProductionEmailCommandListener`, `ProductionQuoteCommandListener`) que evitan publicar mensajes de transacciones revertidas y nunca abortan el commit de negocio si el broker falla. | **CUMPLIDO** |
| **Minimización de Datos (PII) y Seguridad** | Tabla `organizer_contacts` (Flyway `V7`) con enmascaramiento de correo (`ig*********@gmail.com`) en `QuoteCommandPayload` y aislamiento anti-IDOR por `oid`/`sub` en `SecurityUtils`. | **CUMPLIDO** |
| **Compatibilidad Docker / Cluster RabbitMQ** | Soporte nativo para lista de nodos del clúster mediante `SPRING_RABBITMQ_ADDRESSES=rabbitmq-1:5672,rabbitmq-2:5672` sobre la red Docker `eventomax-net`. | **CUMPLIDO** |

---

## 5. Endpoints Expuestos

| Método | Ruta | Roles Autorizados (JWT + Scope `access_as_user`) | Descripción |
|---|---|---|---|
| `GET` | `/api/productions` | `Admin`, `Productor`, `Organizador` | Lista producciones (filtradas automáticamente por `organizerId = token.oid` cuando el rol es exclusivamente `Organizador`). |
| `GET` | `/api/productions/{id}` | `Admin`, `Productor`, `Organizador` | Obtiene detalle de una producción (valida propiedad para `Organizador`). |
| `POST` | `/api/productions` | `Admin`, `Organizador` | Crea una nueva producción en estado `SOLICITADO` y registra evento `ProductionCreated` en Outbox. |
| `PUT` | `/api/productions/{id}/status` | `Admin`, `Productor` | Ejecuta transición en máquina de estados (`SOLICITADO → CONFIRMADO → EN_EJECUCION → CERRADO` o `CANCELADO`), coordina reservas de catálogo y dispara comandos RabbitMQ + eventos Outbox. |
| `GET` | `/api/productions/{id}/services` | `Admin`, `Productor`, `Organizador` | Lista los servicios de catálogo contratados en la producción. |
| `POST` | `/api/productions/{id}/services` | `Admin`, `Productor`, `Organizador` | Contrata un servicio activo de `ms-eventomax-catalog` (en estados `SOLICITADO` o `CONFIRMADO`). |
| `DELETE` | `/api/productions/{id}/services/{serviceId}` | `Admin`, `Productor`, `Organizador` | Descontrata un servicio y emite evento compensatorio hacia Outbox. |
| `POST` | `/api/productions/{id}/quote-command/republish` | `Admin`, `Productor` | Reconstruye desde PostgreSQL y republica de forma segura el comando `quote.gen` para una producción en estado `CERRADO`. |
| `GET` | `/api/organizers/{organizerId}/contact` | `Admin`, `Productor`, `Organizador` | Obtiene el contacto registrado del organizador (valida anti-IDOR para `Organizador`). |
| `PUT` | `/api/organizers/{organizerId}/contact` | `Admin`, `Organizador` | Registra o actualiza correo, nombre y teléfono del organizador con trazabilidad de `updated_by_oid`. |
| `GET` | `/actuator/health`, `/actuator/info` | Público (Healthcheck interno) | Estado de salud del microservicio. |

---

## 6. Seguridad y Autenticación/Autorización JWT

- **Proveedor OAuth2 / OIDC:** Microsoft Entra ID (`spring-boot-starter-oauth2-resource-server`).
- **Validadores (`SecurityConfig`):**
  - `JwtIssuerValidator(issuerUri)`: Valida el emisor exacto del tenant Entra ID (`ENTRA_ISSUER_URI`).
  - `AudienceValidator(audience)`: Valida que el claim `aud` contenga `api://a1a87bcd-fed7-4d65-83c3-338389b80093` (`ENTRA_AUDIENCE`).
  - **Scope obligatorio:** `SCOPE_access_as_user` exigido en todas las rutas `/api/**`.
  - **Roles de aplicación (`roles` claim):** Mapeados a `ROLE_Admin`, `ROLE_Productor`, `ROLE_Organizador`, `ROLE_Auditor`.
  - **Protección Anti-IDOR (`SecurityUtils`):** Si el usuario autenticado tiene rol `Organizador` (sin `Admin` ni `Productor`), se extrae su identificador inmutable (`oid` o `sub`) y se bloquea con `403 Forbidden` cualquier intento de leer o mutar producciones o contactos de otro organizador.

---

## 7. Integración con RabbitMQ

### Topología Utilizada
- **Exchange de Comandos:** `cmd.direct` (tipo `direct`, `durable: true`).
- **Dead Letter Exchange (DLX):** `cmd.dead.dlx` (configurado en las colas consumidoras de Notify).
- **Comandos Publicados:**
  1. `email.send` → Cola `q.cmd.email` (`type = SendProductionStatusEmail`).
  2. `crew.ticket` → Cola `q.cmd.crew` (`type = AssignCrewTicket`, al transicionar a `EN_EJECUCION`).
  3. `quote.gen` → Cola `q.cmd.quote` (`type = GenerateClosingQuote`, al transicionar a `CERRADO`).

### Contrato JSON Real (`MessageEnvelope<QuoteCommandPayload>`)
```json
{
  "type": "GenerateClosingQuote",
  "eventId": "274e40d9-cb8e-4205-93a7-c62605d298bd",
  "timestamp": "2026-10-20T01:15:00Z",
  "traceId": "b3-trace-e2e-20261020",
  "correlationId": "b3-corr-e2e-20261020",
  "payload": {
    "productionId": 11,
    "organizerId": "org-b4-e2e-cert",
    "productionName": "EP2-B4 Certificación Oficial de Dominio EventoMax",
    "status": "CERRADO",
    "scheduledAt": "2026-12-15T20:00",
    "location": "Gran Salón B4 - Santiago",
    "closedAt": "2026-10-20T05:19:15.102Z",
    "createdAt": "2026-10-20T05:19:14.890",
    "organizerContactMaskedEmail": "cl****************@eventomax.cl",
    "organizerContactName": "Dra. Claudia Bahamondes",
    "organizerContactPhone": "+56987654321",
    "services": [
      {
        "serviceId": 101,
        "serviceName": "Sonido Line Array Escenario Principal",
        "unitPrice": 450.00,
        "contractedAt": "2026-10-20T05:19:14.910"
      }
    ],
    "equipment": null,
    "timeline": [
      {
        "sequenceIndex": 1,
        "fromStatus": null,
        "toStatus": "SOLICITADO",
        "occurredAt": "2026-10-20T04:00:00Z",
        "actor": "org-b4-e2e-cert",
        "eventId": "evt-1"
      },
      {
        "sequenceIndex": 2,
        "fromStatus": "EN_EJECUCION",
        "toStatus": "CERRADO",
        "occurredAt": "2026-10-20T05:19:15.102Z",
        "actor": "auditor-b4-e2e-oid",
        "eventId": "274e40d9-cb8e-4205-93a7-c62605d298bd"
      }
    ],
    "kafkaClosingEventId": "274e40d9-cb8e-4205-93a7-c62605d298bd",
    "closingActorOid": "auditor-b4-e2e-oid"
  }
}
```

---

## 8. Integración con Kafka y Patrón Outbox

- Cada mutación de dominio persiste dentro de la **misma transacción ACID** un registro en `outbox_events` (`status = PENDING`).
- Tipos de eventos publicados en el tópico `productions.events`:
  - `ProductionCreated`
  - `ProductionStatusChanged`
  - `ProductionServiceContracted`
  - `ProductionServiceRemoved`
- `OutboxRelay` hace polling periódico de eventos `PENDING`, los publica en Kafka usando `productionId` como clave de partición para garantizar orden por agregado, y marca el evento como `PROCESSED` (o incrementa `retry_count` con estado `FAILED` tras agotar reintentos).

---

## 9. Base de Datos y Migraciones Flyway

Política Hibernate: `spring.jpa.hibernate.ddl-auto=validate`. Todas las estructuras son gobernadas por Flyway (`src/main/resources/db/migration`):

| Versión | Archivo | Descripción |
|---|---|---|
| `V1` | `V1__init_productions.sql` | Crea tablas `productions` y `outbox_events`. |
| `V2` | `V2__align_production_status_constraint.sql` | Alinea constraint de estados (`SOLICITADO`, `CONFIRMADO`, `EN_EJECUCION`, `CERRADO`, `CANCELADO`). |
| `V3` | `V3__create_inventory_reservation_tasks.sql` | Crea tabla de tareas de compensación/verificación de inventario. |
| `V4` | `V4__add_reservation_generation_to_productions.sql` | Añade columna `reservation_generation` (fencing token). |
| `V5` | `V5__align_outbox_events_for_kafka_relay.sql` | Añade metadatos de trazabilidad (`trace_id`, `correlation_id`, `retry_count`, `processed_at`) a `outbox_events`. |
| `V6` | `V6__create_production_contracted_services.sql` | Crea tabla `production_contracted_services` para contratación de servicios. |
| `V7` | `V7__create_organizer_contacts_table.sql` | Crea tabla `organizer_contacts` con índice sobre `user_oid` y restricción única parcial de contacto activo por organizador. |

---

## 10. Variables de Entorno Requeridas

| Variable | Ejemplo Placeholder | Descripción |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/eventomax_productions` | URL JDBC PostgreSQL |
| `DB_USER` | `productions_user` | Usuario PostgreSQL |
| `DB_PASSWORD` | `<SECRET_DB_PASSWORD>` | Contraseña PostgreSQL |
| `SPRING_RABBITMQ_ADDRESSES` | `rabbitmq-1:5672,rabbitmq-2:5672` | Direcciones del clúster RabbitMQ en Docker/AWS |
| `RABBITMQ_HOST` | `localhost` | Host RabbitMQ individual (fallback local) |
| `RABBITMQ_PORT` | `5672` (o `25692` en demo local) | Puerto AMQP |
| `RABBITMQ_VHOST` | `/` | Virtual host RabbitMQ |
| `RABBITMQ_USERNAME` | `<RABBITMQ_APP_USER>` | Usuario de aplicación con mínimo privilegio |
| `RABBITMQ_PASSWORD` | `<SECRET_RABBITMQ_PASSWORD>` | Contraseña RabbitMQ |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Brokers Apache Kafka |
| `KAFKA_TOPIC_PRODUCTIONS` | `productions.events` | Tópico de eventos de dominio |
| `CATALOG_BASE_URL` | `http://ms-eventomax-catalog:8080` | URL interna del microservicio Catalog |
| `ENTRA_ISSUER_URI` | `https://login.microsoftonline.com/<TENANT_ID>/v2.0` | Issuer URI de Microsoft Entra ID |
| `ENTRA_AUDIENCE` | `api://a1a87bcd-fed7-4d65-83c3-338389b80093` | Audience del API registrada en Entra ID |

---

## 11. Ejecución Local Paso a Paso

```bash
# 1. Levantar brokers locales (RabbitMQ y Kafka en infra-eventomax) y PostgreSQL
# 2. Exportar variables de entorno requeridas (sin hardcodear secretos)
export DB_URL="jdbc:postgresql://localhost:5432/eventomax_productions"
export DB_USER="postgres"
export DB_PASSWORD="<TU_PASSWORD_LOCAL>"
export RABBITMQ_HOST="localhost"
export RABBITMQ_PORT="25692"
export RABBITMQ_USERNAME="eventomax-app"
export RABBITMQ_PASSWORD="<TU_PASSWORD_RABBIT>"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export CATALOG_BASE_URL="http://localhost:8080"
export ENTRA_ISSUER_URI="https://login.microsoftonline.com/f191bdc8-7926-4722-9ec0-cba663701940/v2.0"
export ENTRA_AUDIENCE="api://a1a87bcd-fed7-4d65-83c3-338389b80093"

# 3. Ejecutar el microservicio
mvn spring-boot:run
```

---

## 12. Ejecución de Pruebas Automatizadas

```bash
# Ejecutar suite completa de pruebas unitarias y de integración (212 tests)
mvn clean test

# Ejecutar prueba de integración E2E de dominio contra broker RabbitMQ real (cuando el entorno vivo está activo)
mvn test -Dtest=ProductionDomainQuoteE2EIT
```

**Resultado certificado en EP2:** `Tests run: 212, Failures: 0, Errors: 0, Skipped: 1 — BUILD SUCCESS`.

---

## 13. Construcción y Despliegue Docker / Preparación Cloud

```bash
# Construcción de imagen multi-stage (Maven JDK 25 -> Eclipse Temurin JRE 25 non-root)
docker build -t ms-eventomax-productions:ep2 .

# Despliegue productivo sobre la red compartida eventomax-net
docker compose -f docker-compose.prod.yml up -d --build
```

---

## 14. Estructura del Proyecto

```text
ms-eventomax-productions/
├── src/main/java/cl/duoc/eventomax/productions/
│   ├── config/               # Configuración general y clientes REST
│   ├── controller/           # ProductionController, OrganizerContactController
│   ├── dto/                  # DTOs de entrada/salida y validación
│   ├── event/                # Eventos internos Spring y sobres Kafka
│   ├── exception/            # Manejo centralizado de errores HTTP
│   ├── integration/catalog/  # Cliente resiliente hacia ms-eventomax-catalog
│   ├── messaging/
│   │   ├── common/           # MessageEnvelope<T>
│   │   ├── kafka/            # OutboxRelay
│   │   └── rabbitmq/         # RabbitMQCommandPublisher, Listeners y Payloads
│   ├── model/                # Entidades JPA (Production, OutboxEvent, OrganizerContact, etc.)
│   ├── repository/           # Repositorios Spring Data JPA
│   ├── security/             # SecurityConfig, AudienceValidator, SecurityUtils
│   └── service/              # ProductionService, ProductionStateCoordinator, OrganizerContactService
├── src/main/resources/
│   ├── db/migration/         # Migraciones Flyway V1..V7
│   └── application.yml       # Configuración externalizada
├── Dockerfile
└── docker-compose.prod.yml
```

---

## 15. Flujo Funcional Demostrado en EP2

1. Un `Organizador` registra sus datos de contacto (`PUT /api/organizers/{id}/contact`) y crea una producción (`POST /api/productions`).
2. Se contratan servicios del catálogo (`POST /api/productions/{id}/services`).
3. Un `Productor` o `Admin` avanza la producción por `CONFIRMADO` (reservando equipos en Catalog), `EN_EJECUCION` (publicando `crew.ticket` hacia Notify) y finalmente `CERRADO`.
4. Al confirmar el commit de `CERRADO`, `ProductionQuoteCommandListener` ensambla el `QuoteCommandPayload` enriquecido y `RabbitMQCommandPublisher` lo publica en `cmd.direct` (`quote.gen`) esperando el ACK correlacionado del broker.
5. `ms-eventomax-notify` consume el mensaje desde `q.cmd.quote` y genera el Acta de Cierre PDF empresarial descargable desde el frontend Angular.

---

## 16. Troubleshooting y Errores Comunes

- **Error `403 Forbidden` al consultar una producción con rol `Organizador`:** Verifique que el claim `oid` (o `sub`) del token JWT coincida con el `organizerId` de la producción.
- **`Confirm ACK=false` o `TimeoutException` en `RabbitMQCommandPublisher`:** Verifique conectividad hacia `SPRING_RABBITMQ_ADDRESSES` y que el exchange `cmd.direct` y la cola `q.cmd.quote` estén declarados en el vhost `/`. Use `POST /api/productions/{id}/quote-command/republish` para reintentar una vez restablecido el broker.
- **Error de validación Flyway al iniciar:** Verifique que PostgreSQL tenga aplicadas las migraciones `V1` a `V7` sin modificaciones manuales de checksum.

---

## 17. Estado Actual de Certificación EP2

- **Compilación y Empaquetado:** Certificado (`Java 25`, `Spring Boot 4.1.1`).
- **Pruebas Automatizadas:** `212/212` aprobadas (incluyendo aislamiento JWT/IDOR, concurrencia de inventario, Outbox, Publisher Confirms y E2E RabbitMQ).
- **Seguridad:** Cero credenciales hardcodeadas; validación estricta de `issuer`, `audience`, `scope` y `roles`.

---

## 18. Trazabilidad Jira / Historias Relacionadas

- **EMX-92:** Implementación de patrón Outbox y publicación de eventos de producción en Kafka.
- **EMX-Bloque-1A:** Coordinación transaccional de reservas de inventario y aislamiento por organizador.
- **EP2-FIX-PDF-01 (B1 / B2.2 / PRE-B3 / B3 / B4):** Persistencia de contactos de organizador (`V7`), enriquecimiento de `QuoteCommandPayload`, republicación operativa, Publisher Confirms correlacionados y certificación E2E con RabbitMQ y OpenPDF.
