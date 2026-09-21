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
- Docker

## Responsabilidades

`ms-eventomax-productions` debe:

- Crear y consultar eventos.
- Gestionar el ciclo de estados de una producción.
- Validar reglas de negocio asociadas a los estados.
- Persistir la información propia del dominio de producciones.
- Exponer operaciones bajo `/api/productions/*`.

## Estados de evento

El ciclo principal definido para EventoMax es:

`SOLICITADO → CONFIRMADO → EN_MONTAJE → EN_EJECUCION → CERRADO`

También existe el estado:

`CANCELADO`

Regla principal:

No se debe permitir pasar a `EN_MONTAJE` sin haber pasado previamente por `CONFIRMADO`.

Las reglas de transición deben validarse en backend.

## Arquitectura

El microservicio forma parte del flujo seguro de EventoMax:

`Angular → Microsoft Entra ID → JWT → AWS API Gateway → ms-eventomax-bff → ms-eventomax-productions → Amazon RDS for PostgreSQL`

El frontend no accede directamente a este servicio ni a su base de datos.
El servicio no es público y recibe tráfico protegido y orquestado exclusivamente desde el BFF.

No se debe agregar Spring Security directamente a Productions. La seguridad JWT/roles/scopes se aplica en API Gateway + BFF para este alcance.

*Nota: La integración avanzada con cuadrillas mediante RabbitMQ o Kafka, notificaciones, auditoría y reportería pertenecen a etapas posteriores del semestre, fuera del alcance inicial (EP1).*

## Persistencia

El microservicio utilizará PostgreSQL mediante:

- Spring Data JPA
- Hibernate
- Flyway

En cloud se utilizará **Amazon RDS for PostgreSQL**.

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
No hay valores de respaldo en `application.yml`: las tres variables deben definirse
tanto localmente como en producción. Los archivos privados `application-local.*` y
`application-secrets.*` no se incluyen en el JAR ni en la imagen Docker.
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

- `id`, `organizerId`, `name`, `scheduledAt`, `location`, `status`, `createdAt`, `updatedAt`

El request de creación acepta `organizerId`, `name`, `scheduledAt` y `location`.
El backend establece `SOLICITADO` como estado inicial.

### EMX-50 – API REST mínima

**Endpoints disponibles:**

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/api/productions` | Crear producción (estado inicial: `SOLICITADO`) |
| `GET` | `/api/productions` | Listar producciones (soporta filtros: `status`, `from`, `to`) |
| `GET` | `/api/productions/{id}` | Obtener producción por ID |
| `PUT` | `/api/productions/{id}/status` | Actualizar estado con validación de transición |

**Manejo de errores uniforme:**

| HTTP | Situación |
|------|-----------|
| `201` | Creación exitosa |
| `400` | Validación de campos del request |
| `404` | Producción no encontrada |
| `409` | Transición de estado inválida |

**Documentación Interactiva:**

- **Swagger UI:** [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
- **OpenAPI JSON:** [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

### EMX-51 – Pruebas del slice y Hardening EP1

Tests implementados y pasando (`mvn clean test`):

- `MsEventomaxProductionsApplicationTests` – arranque del contexto
- `ProductionPersistenceTest` – esquema Flyway + persistencia JPA
- `ProductionControllerTest` – slice `@WebMvcTest` con MockMvc
- `ProductionServiceTest` – lógica de transición de estados terminales
- `ProductionApiIntegrationTest` – filtros y timestamps con Controller/Service/JPA reales

La suite incluye pruebas de integración de filtros y timestamps con persistencia real
del perfil `test`. Consultar el resumen de Maven para el total actualizado: se exige
`Failures: 0`, `Errors: 0`, `Skipped: 0` y `BUILD SUCCESS`.
H2 no sustituye PostgreSQL: antes de promover una versión, ejecutar también
`ProductionApiIntegrationTest` contra una base PostgreSQL desechable.

## Ejecución

El proyecto está preparado para ejecutarse de dos maneras en entornos locales:

### Opción 1: Docker Compose (Recomendado)

Levanta la base de datos PostgreSQL y la aplicación de Spring Boot en contenedores enlazados.

1. Copiar la plantilla de variables de entorno y completarla si se requiere:

```bash
cp .env.example .env
```

Dentro de Docker Compose local, el hostname PostgreSQL es `postgres`.

2. Construir y levantar los servicios:

```powershell
docker compose --env-file .env up -d --build
```

La aplicación queda publicada localmente en:
```text
localhost:8082
```

### Opción 2: Ejecución Nativa

Permite ejecutar la aplicación directamente en la máquina host, conectándose a la base de datos de Docker expuesta en localhost. Ideal para debug con IDEs.

1. Levantar solo la base de datos:
```bash
docker compose up postgres -d
```

2. Compilar y ejecutar nativamente configurando las variables de entorno:
```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/<DB_LOCAL>"
$env:DB_USER="<USUARIO_LOCAL>"
$env:DB_PASSWORD="<PASSWORD_LOCAL>"
.\mvnw.cmd spring-boot:run
```

Fuera de Docker, ejecutando Spring Boot nativamente, normalmente se utiliza `localhost`.

3. Validar health check:

```
GET http://localhost:8080/actuator/health
```

Respuesta esperada:

```json
{ "status": "UP" }
```

4. Detener los servicios:

```bash
docker compose down
```

## Preparación AWS (Producción)

El microservicio está preparado para despliegue en la arquitectura EventoMax sobre AWS mediante el archivo `docker-compose.prod.yml`. Este archivo:

- contiene solamente Productions
- no levanta PostgreSQL
- consume `DB_URL`, `DB_USER`, `DB_PASSWORD`
- usa la red externa `eventomax-net`
- no publica el puerto 8080 al host
- permite al BFF resolver internamente:

```text
http://ms-eventomax-productions:8080
```

Las variables `DB_URL`, `DB_USER` y `DB_PASSWORD` serán inyectadas en la instancia EC2.
El healthcheck Docker consulta `/actuator/health` dentro del contenedor.
El ALB/API Gateway accede al BFF; Productions no se registra como destino público.
La demo oficial corre en AWS EC2 con RDS y no depende de un computador local.

En EC2, crear `eventomax-net` si aún no existe y conectar también el BFF a esa red.
Configurar las variables `DB_*` externamente para RDS y verificar conectividad/TLS.
Desde el repositorio en la instancia:

```bash
docker network inspect eventomax-net >/dev/null 2>&1 || docker network create eventomax-net
docker compose -f docker-compose.prod.yml config --quiet
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
```

No combinar el Compose local con el de producción. Validar vía API Gateway/BFF:
401 sin JWT, lectura 200 con JWT autorizado, POST 201 y transición válida 200.
Comprobar además filtros opcionales, 400 para un rango `from > to`, 404 para ID
inexistente y 409 para transición inválida. El PUT devuelve el `updatedAt` persistido;
las lecturas posteriores no lo modifican.

Los filtros de fecha usan `scheduledAt`, límites inclusivos y fecha/hora local ISO-8601
sin offset. El estado se envía como `EN_EJECUCION` (sin tilde).

## Proyecto académico

**Asignatura:** DSY1107 – Desarrollo Cloud Native I
**Caso:** Caso 8 – EventoMax
