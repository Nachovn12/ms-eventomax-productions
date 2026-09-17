# ---- Stage 1: Build ----
FROM maven:3-eclipse-temurin-25 AS build

WORKDIR /app

# Descargar dependencias primero (capa cacheada)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copiar código fuente y empaquetar
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:25-jre

WORKDIR /app

# Instalar curl para healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Crear usuario no root
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --no-create-home appuser

# Copiar jar desde stage de build
COPY --from=build /app/target/ms-eventomax-productions-0.0.1-SNAPSHOT.jar app.jar

# Puerto de la aplicación
EXPOSE 8080

# Healthcheck via Actuator
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl --fail http://localhost:8080/actuator/health || exit 1

# Ejecutar como usuario no root
USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]
