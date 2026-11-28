# Construccion en dos etapas con cache de dependencias Maven (BuildKit) e imagenes fijadas por version.
# El contexto de build es la RAIZ del repositorio (ver docker-compose.yml).
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
ENV MAVEN_ARGS="-Daether.connector.http.retryHandler.count=5 -Daether.connector.http.retryHandler.requestSentEnabled=true"

# 1) Copiar solo los POMs del reactor para cachear la capa de dependencias
COPY pom.xml .
COPY tpi-common/pom.xml tpi-common/
COPY ms-animales/pom.xml ms-animales/
COPY ms-adopciones/pom.xml ms-adopciones/
COPY ms-donaciones/pom.xml ms-donaciones/
COPY gateway/pom.xml gateway/
COPY coverage-report/pom.xml coverage-report/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl gateway -am dependency:go-offline

# 2) Copiar unicamente el codigo fuente del modulo y empaquetar
COPY gateway/src gateway/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl gateway -am package -DskipTests

# Etapa 2: runtime minimo JRE 21 fijado con usuario sin privilegios y metadatos OCI
FROM eclipse-temurin:24.0.2_12-jre-noble
ARG IMAGE_VERSION=1.0.0
ARG VCS_REF=dev
LABEL org.opencontainers.image.title="gateway" \
      org.opencontainers.image.description="API Gateway reactivo (Spring Cloud Gateway WebFlux) para adopcion de animales" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.source="https://github.com/GinoPiersigilli/Backend-Mascotas"

RUN groupadd -r app && useradd -r -g app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/gateway/target/app.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
