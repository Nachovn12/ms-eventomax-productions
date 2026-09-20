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

## Ejecución

### Docker Compose (recomendado)

El entorno local de desarrollo puede levantar PostgreSQL y la aplicación mediante Docker Compose.

1. Copiar la plantilla:

```bash
cp .env.example .env
```

2. Completar únicamente los valores locales necesarios y levantar:

```bash
docker compose up --build -d
```

3. Validar health:

```text
GET http://localhost:8080/actuator/health
```

4. Validar API:

```text
GET http://localhost:8080/api/productions
```

5. Detener:

```bash
docker compose down
```

### Ejecución nativa

La configuración versionada requiere las variables de entorno `DB_URL`, `DB_USER` y `DB_PASSWORD`; no se mantienen credenciales de respaldo en `application.yml`.

Windows:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/<database>"
$env:DB_USER = "<user>"
$env:DB_PASSWORD = "<password>"
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```bash
export DB_URL="jdbc:postgresql://localhost:5432/<database>"
export DB_USER="<user>"
export DB_PASSWORD="<password>"
./mvnw clean verify
./mvnw spring-boot:run
```

No guardar credenciales reales en archivos versionados.

## Preparación AWS

El microservicio está preparado para despliegue en la arquitectura EventoMax sobre AWS:

- **Cómputo:** la imagen Docker se desplegará en **Amazon EC2**.
- **Base de datos:** PostgreSQL productivo será provisto por **Amazon RDS for PostgreSQL**.
- **Configuración:** las variables `DB_URL`, `DB_USER` y `DB_PASSWORD` serán inyectadas mediante **variables de entorno** o **AWS Secrets Manager** en la instancia EC2.
- **Seguridad:** no se subirán archivos `.env` reales al repositorio. Las credenciales productivas se gestionan exclusivamente en la infraestructura AWS.
- **Health check:** el endpoint `/actuator/health` está disponible para monitoreo del **Application Load Balancer** y Docker HEALTHCHECK.

## Proyecto académico

**Asignatura:** DSY1107 – Desarrollo Cloud Native I  
**Caso:** Caso 8 – EventoMax
