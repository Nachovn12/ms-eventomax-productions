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
La configuración principal está en `src/main/resources/application.yml` y requiere
`DB_URL` (URL JDBC de PostgreSQL), `DB_USER` y `DB_PASSWORD` como variables de entorno,
sin valores de respaldo. No guardar credenciales en archivos versionados.

Compilar y verificar en Windows:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd clean package
```

En Linux/macOS, usar `./mvnw` en lugar de `.\mvnw.cmd`.
El JAR ejecutable se genera en `target/ms-eventomax-productions-0.0.1-SNAPSHOT.jar`.

El test de arranque activa el perfil `test`, configurado únicamente en
`src/test/resources/application-test.yml`: H2 en modo PostgreSQL, Flyway habilitado
y `ddl-auto=validate`. H2 es una dependencia exclusiva de tests. Esta prueba verifica
el arranque y la validación del esquema actual; no sustituye pruebas del slice EP1
ni la validación contra PostgreSQL/RDS.

### Checkpoint pendiente de EMX-49

EMX-39 define la representación mínima: `id`, `organizerId`, `name`, `scheduledAt`,
`location`, `status`, `createdAt`, `updatedAt`. El request contiene únicamente
`name`, `scheduledAt` y `location`; la identidad determina `organizerId` cuando
corresponda y el backend establece `SOLICITADO` como estado inicial.

La entidad y V1 aún reflejan el modelo preliminar. Antes de alinearlas, consultar
`flyway_schema_history` en la base compartida `eventomax_productions` y registrar
el resultado. Si V1 ya fue aplicada, conservarla y crear V2; si no fue aplicada,
corregir V1. La falta de conexión o de evidencia no demuestra que V1 esté sin aplicar.
EMX-50 (API) y EMX-51 (pruebas del slice y Docker) siguen pendientes.

## Proyecto académico

**Asignatura:** DSY1107 – Desarrollo Cloud Native I  
**Caso:** Caso 8 – EventoMax
