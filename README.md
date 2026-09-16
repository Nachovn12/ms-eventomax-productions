# EventoMax Productions

Microservicio de dominio de **EventoMax** responsable de la gestión de eventos, estados y coordinación de producción.

## Tecnologías

- Java 25 LTS
- Spring Boot 4.1.1 GA
- Spring Data JPA
- Hibernate
- Flyway
- PostgreSQL
- Maven
- OpenAPI / Swagger

## Responsabilidades

`ms-eventomax-productions` debe:

- Crear y consultar eventos.
- Gestionar el ciclo de estados de una producción.
- Coordinar la asignación de cuadrillas.
- Validar reglas de negocio asociadas a los estados.
- Persistir la información propia del dominio de producciones.
- Exponer operaciones bajo `/api/productions/*`.

## Estados de evento

El ciclo principal definido para EventoMax es:

`SOLICITADO → CONFIRMADO → EN_MONTAJE → EN_EJECUCIÓN → CERRADO`

También existe el estado:

`CANCELADO`

Regla principal:

No se debe permitir pasar a `EN_MONTAJE` sin haber pasado previamente por `CONFIRMADO`.

Las reglas de transición deben validarse en backend.

## Arquitectura

El microservicio forma parte del flujo seguro de EventoMax:

`Angular → Microsoft Entra ID → JWT → AWS API Gateway → ms-eventomax-bff → ms-eventomax-productions → PostgreSQL`

El frontend no accede directamente a este servicio ni a su base de datos.

## Persistencia

El microservicio utilizará PostgreSQL mediante:

- Spring Data JPA
- Hibernate
- Flyway

En cloud se utilizará Amazon RDS for PostgreSQL.

El servicio es propietario de sus propios datos y no debe realizar consultas SQL directas sobre datos internos de otros microservicios.

## Seguridad

El acceso protegido llegará a través de:

`AWS API Gateway → ms-eventomax-bff → ms-eventomax-productions`

La autenticación y autorización se basan en JWT emitidos por Microsoft Entra ID.

No se deben almacenar en este repositorio:

- Client Secrets
- Access Tokens
- credenciales AWS
- credenciales PostgreSQL
- archivos `.env` reales
- passwords o claves privadas

## Estrategia de ramas

- `main`: versión estable y preparada para entrega.
- `develop`: rama de integración.
- `feature/*`: desarrollo de historias de usuario.
- `fix/*`: correcciones.
- `chore/*`: configuración e infraestructura.

Flujo de integración:

`feature/* → Pull Request → develop → pruebas → Pull Request → main`

## Ejecución local

Requisitos: JDK 25 y acceso a PostgreSQL para ejecutar la aplicación.
La configuración principal está en `src/main/resources/application.yml`.
Las variables de entorno requeridas son `DB_URL`, `DB_USER` y `DB_PASSWORD`.
En desarrollo local se usan valores de respaldo definidos en `application.yml`.
En producción deben inyectarse externamente (sin valores de respaldo).
No guardar credenciales reales en archivos versionados.

Compilar y verificar en Windows:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd clean package
```

En Linux/macOS, usar `./mvnw` en lugar de `.\mvnw.cmd`.
El JAR ejecutable se genera en `target/ms-eventomax-productions-0.0.1-SNAPSHOT.jar`.

El test de arranque activa el perfil `test`, configurado únicamente en
`src/test/resources/application-test.yml`: H2 en modo PostgreSQL, Flyway habilitado
y `ddl-auto=validate`. H2 es una dependencia exclusiva de tests.

### EMX-49 – Persistencia mínima de Production

EMX-49 deja alineada la persistencia mínima de `Production` con el contrato EP1
aprobado en EMX-39:

- `id`
- `organizerId`
- `name`
- `scheduledAt`
- `location`
- `status`
- `createdAt`
- `updatedAt`

El request de creación acepta `organizerId`, `name`, `scheduledAt` y `location`.
El backend establece `SOLICITADO` como estado inicial.
`organizerId` pasará a derivarse del JWT cuando se integre la capa de seguridad.

La migración `V1__create_productions_table.sql` fue corregida directamente antes de
su primera aplicación en el entorno compartido. La verificación realizada sobre
Amazon RDS confirmó que no existían ni `flyway_schema_history` ni la tabla
`public.productions`, por lo que no correspondía crear una migración V2.

### EMX-50 – API REST mínima (completado)

EMX-50 implementa la API REST completa sobre el dominio `Production`:

**Endpoints disponibles:**

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/api/productions` | Crear producción (estado inicial: `SOLICITADO`) |
| `GET` | `/api/productions` | Listar todas las producciones |
| `GET` | `/api/productions/{id}` | Obtener producción por ID |
| `PATCH` | `/api/productions/{id}/status` | Actualizar estado con validación de transición |

**Reglas de transición implementadas:**

```
SOLICITADO  → CONFIRMADO | CANCELADO
CONFIRMADO  → EN_MONTAJE | CANCELADO
EN_MONTAJE  → EN_EJECUCION | CANCELADO
EN_EJECUCION → CERRADO | CANCELADO
CERRADO     → (terminal)
CANCELADO   → (terminal)
```

**Manejo de errores uniforme:**

| HTTP | Situación |
|------|-----------|
| `400` | Validación de campos del request |
| `404` | Producción no encontrada |
| `422` | Transición de estado inválida |

**Documentación:** disponible en `/swagger-ui/index.html` y `/v3/api-docs`.

### EMX-51 – Pruebas del slice (completado en rama EMX-50)

Tests implementados y pasando (`mvn test`):

- `MsEventomaxProductionsApplicationTests` – arranque del contexto
- `ProductionPersistenceTest` – esquema Flyway + persistencia JPA (2 tests)
- `ProductionControllerTest` – slice `@WebMvcTest` con MockMvc (6 tests)

Total: **9 tests, 0 fallos**.

## Proyecto académico

**Asignatura:** DSY1107 – Desarrollo Cloud Native I  
**Caso:** Caso 8 – EventoMax
