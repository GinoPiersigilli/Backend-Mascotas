# Construccion en dos etapas con cache de dependencias Maven (BuildKit).
# El contexto de build es la RAIZ del repositorio (ver docker-compose.yml).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 1) Copiar solo los POMs del reactor para cachear la capa de dependencias
COPY pom.xml .
COPY tpi-common/pom.xml tpi-common/
COPY ms-animales/pom.xml ms-animales/
COPY ms-adopciones/pom.xml ms-adopciones/
COPY ms-donaciones/pom.xml ms-donaciones/
COPY gateway/pom.xml gateway/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl gateway -am dependency:go-offline

# 2) Copiar unicamente el codigo fuente del modulo y empaquetar
COPY gateway/src gateway/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl gateway -am package -DskipTests

# Etapa 2: runtime minimo con usuario sin privilegios
FROM eclipse-temurin:21-jre
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/gateway/target/app.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
