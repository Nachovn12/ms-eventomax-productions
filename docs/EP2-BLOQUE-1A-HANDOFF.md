# Handoff Técnico - EventoMax EP2: Bloque 1A (Productions)

- **Repositorio:** `ms-eventomax-productions`
- **Rama:** `feature/EMX-bloque-1a-productions`
- **Fecha:** Octubre 2026
- **Estado del bloque:** Implementado y verificado localmente.

---

## 1. Alcance Implementado

En este bloque se implementó la resiliencia en la confirmación de producciones con reserva de inventario, mitigando el problema de dual-write y estados inconsistentes entre microservicios:

1. **Tabla de Tareas de Reserva (Flyway V4):**
   - Script `V4__create_inventory_reservation_tasks_table.sql`: Crea la tabla `inventory_reservation_tasks` para auditar y controlar el ciclo de vida de la reserva (`PENDING`, `COMPLETED`, `FAILED`, `COMPENSATED`).
   - Modelo JPA `InventoryReservationTask` y repositorio `InventoryReservationTaskRepository`.

2. **Flujo de Confirmación Resiliente en `ProductionService`:**
   - Previo a la llamada REST externa, se registra la tarea de reserva.
   - Si la reserva en Catalog responde exitosamente, la tarea pasa a `COMPLETED` y la producción cambia a `CONFIRMADA`.
   - **Manejo de Timeouts de Red:** En caso de timeout o error transitorio de red, se efectúa una consulta de verificación (`getReservationsByProduction`). Si la reserva ya fue creada en Catalog, se procede normalmente.
   - **Compensación Técnica Inmediata:** Si la confirmación local falla tras haber reservado stock, se ejecuta de inmediato `compensateInventory` hacia Catalog para liberar el inventario.

3. **Worker Asíncrono de Recuperación:**
   - `InventoryReservationRecoveryWorker`: Proceso periódico programado (`@Scheduled`) que identifica tareas huérfanas o con fallo de compensación síncrona y reintenta la liberación de reservas en Catalog de forma idempotente.

---

## 2. Evidencia de Pruebas Locales

- **Pruebas unitarias y de integración:** 108/108 PASS (`mvn clean test`).
- Cobertura completa de los casos de éxito, agotamiento de stock, reintentos por timeout, rollback local con compensación síncrona y recuperación en segundo plano vía `InventoryReservationRecoveryWorkerTest`.
- Ejecutado con Testcontainers (PostgreSQL 17 y Kafka outbox relay).

---

## 3. Limitaciones Conocidas y Trabajo Pendiente

> [!WARNING]
> No se declara 100% verificado ni listo para producción. Quedan pendientes pruebas de integración distribuida (E2E) con los demás microservicios en red compartida y despliegue real en infraestructura cloud/AWS.

- Pruebas E2E de comunicación real entre `ms-eventomax-productions` y `ms-eventomax-catalog` a través de red Docker / Kubernetes.
- Integración con colas RabbitMQ / DLQ según pauta del encargo (Bloque 2).
- Bloques funcionales posteriores (1B, 2, 3 y 4).

---

## 4. Instrucciones para Continuar en el Laboratorio

### Requisitos previos:
- JDK 21 o superior.
- Docker Desktop activo (para PostgreSQL y Kafka en Testcontainers).
- Maven 3.9+ o script `./mvnw`.

### Comandos de clonado y ejecución:
```bash
git clone https://github.com/Nachovn12/ms-eventomax-productions.git
cd ms-eventomax-productions
git checkout feature/EMX-bloque-1a-productions

# Compilar y ejecutar pruebas unitarias e integración
./mvnw clean test
```
