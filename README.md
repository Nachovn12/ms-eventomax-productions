# EventoMax Productions

Microservicio de dominio de **EventoMax** responsable de la gestión de eventos, estados y coordinación de producción.

## Tecnologías

- Java 25 LTS
- Spring Boot
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

Las instrucciones de compilación y ejecución se completarán cuando se inicialice el proyecto Spring Boot.

## Proyecto académico

**Asignatura:** DSY1107 – Desarrollo Cloud Native I  
**Caso:** Caso 8 – EventoMax
