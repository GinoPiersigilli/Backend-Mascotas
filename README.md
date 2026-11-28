# Adopción Responsable — Backend de Microservicios

[![CI](https://github.com/GinoPiersigilli/Backend-Mascotas/actions/workflows/ci.yml/badge.svg)](https://github.com/GinoPiersigilli/Backend-Mascotas/actions/workflows/ci.yml)
[![Security](https://github.com/GinoPiersigilli/Backend-Mascotas/actions/workflows/security.yml/badge.svg)](https://github.com/GinoPiersigilli/Backend-Mascotas/actions/workflows/security.yml)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Spring Boot 3.5.16](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Coverage 90.1%](https://img.shields.io/badge/JaCoCo%20Lines-90.1%25-brightgreen)](docs/ARQUITECTURA.md)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> **Executive Summary (English)**
> Production-style distributed backend coordinating the end-to-end animal shelter adoption lifecycle and donation processing, built with **Java 21 (Virtual Threads)**, **Spring Boot 3.5.16**, **Spring Cloud Gateway (WebFlux)**, **PostgreSQL 17**, and **Keycloak 26.8 (OIDC/JWT)**.
> Three domain-isolated microservices (`ms-animales`, `ms-adopciones`, `ms-donaciones`) own separate PostgreSQL schemas and credentials migrated via **Flyway** (`ddl-auto: validate`), behind a single reactive API Gateway enforcing strict JWT validation (`iss`, `exp` 30s clock skew, `azp` client allowlists), security headers, payload caps (`413`), and end-to-end `X-Correlation-ID` tracing.
> Cross-service state transitions avoid 2PC and premature broker complexity through local-first optimistic locking (`@Version`), idempotent internal transitions (`solicitudId`), and **ShedLock**-coordinated background reconciliation.
> External integrations (**Argentina Georef API** and **Mercado Pago Checkout Pro** with constant-time HMAC-SHA256 `x-signature` webhooks and `Idempotency-Key` support) are protected by **Resilience4j** circuit breakers and GET-only retries.
> Verified continuously by **237 unit/slice tests** (**90.1%** aggregate line coverage), **ArchUnit**, **Checkstyle**, **SpotBugs**, **Testcontainers PostgreSQL 17**, an **8-container E2E smoke suite** (WireMock), and **CodeQL / Trivy / gitleaks** scans.

---

## Tabla de contenidos

1. [Problema y alcance de negocio](#1-problema-y-alcance-de-negocio)
2. [Demo reproducible en 3 comandos](#2-demo-reproducible-en-3-comandos)
3. [Arquitectura y módulos](#3-arquitectura-y-módulos)
4. [Decisiones de ingeniería destacadas](#4-decisiones-de-ingeniería-destacadas)
5. [Seguridad, observabilidad y rendimiento](#5-seguridad-observabilidad-y-rendimiento)
6. [Calidad automatizada y cobertura medida](#6-calidad-automatizada-y-cobertura-medida)
7. [Trade-offs y limitaciones conocidas](#7-trade-offs-y-limitaciones-conocidas)
8. [Índice de documentación](#8-índice-de-documentación)

---

## 1. Problema y alcance de negocio

Muchos refugios coordinan adopciones por mensajería informal y planillas aisladas: varias personas compiten simultáneamente por el mismo animal, se aprueban entregas sin verificar compatibilidad del hogar ni distancia geográfica, no hay alertas sobre seguimientos post-entrega vencidos y las donaciones quedan desvinculadas de la confirmación real del cobro.

Este sistema modela cinco procesos integrados con máquinas de estados explícitas e historial inmutable de auditoría:
- **P1 — Catálogo y disponibilidad (`ms-animales`):** alta, edición y máquina de estados (`DISPONIBLE`, `RESERVADO`, `EN_ADAPTACION`, `ADOPTADO`, `NO_DISPONIBLE`, regla `R-13`).
- **P2 — Evaluación y decisión (`ms-adopciones`):** validación de perfil (`R-03`), unicidad concurrente por adoptante y animal (`R-01`, `R-02`), incompatibilidades duras (`R-04`) y observaciones (`R-05`), geolocalización con **API Georef** + distancia Haversine con degradación no bloqueante (`R-06`) y aprobación/rechazo con reserva coordinada (`R-07`, `R-08`).
- **P3 — Entrega con plazo (`ms-adopciones`):** confirmación dentro de ventana configurable (`R-09`, `R-10`) y cancelación automática de reservas vencidas mediante tarea programada distribuida.
- **P4 — Adaptación, alertas y cierre (`ms-adopciones`):** alertas automáticas de controles finales vencidos, control final conforme/desfavorable (`R-11`, `R-12`), devolución voluntaria, reportes agregados para Coordinadores y reconciliación periódica/manual contra `ms-animales`.
- **P5 — Donaciones (`ms-donaciones`):** preferencias **Mercado Pago Checkout Pro** (`D-01..D-04`) con `Idempotency-Key` acotada por donante (`SHA-256`, `409` ante discrepancia de monto) y confirmación idempotente vía **webhook firmado HMAC-SHA256**, reconciliación programada y verificación bajo demanda.

---

## 2. Demo reproducible en 3 comandos

El perfil `docker-compose.demo.yml` levanta los **8 contenedores** (Gateway, 3 microservicios, Keycloak, 2 instancias PostgreSQL 17 y **WireMock** simulando Georef y Mercado Pago con los stubs de [`infra/wiremock/mappings/`](infra/wiremock/mappings)) sin requerir credenciales externas ni acceso a APIs de terceros:

```bash
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build -d --wait
EXPECT_DONACIONES=true bash ./scripts/prueba-humo.sh
```

`prueba-humo.sh` obtiene tokens reales en Keycloak (`adoptante1`, `adoptante2`, `coordinador1`), verifica propagación de `X-Correlation-ID`, recorre el ciclo P1–P5 completo, valida RBAC/IDOR (`401`/`403`/`409`), reportes e `Idempotency-Key`, y confirma la transición de donación a `CONFIRMADA`.

Otros modos de ejecución (detalle completo en [`docs/OPERACION.md`](docs/OPERACION.md)):
```bash
# Desarrollo local (expone :8081-:8083, Postgres :5433 y activa Swagger UI)
docker compose up --build -d --wait

# Perfil productivo base (solo publica Gateway :8080 y Keycloak :8180; SWAGGER_ENABLED=false)
docker compose -f docker-compose.yml up --build -d --wait
```

---

## 3. Arquitectura y módulos

Diagramas Mermaid detallados (topología, secuencias P2–P4 y P5, pipeline CI/CD) en [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md), además de los diagramas C4 SVG en [`docs/diagramas/`](docs/diagramas):

![C2 - Diagrama de Contenedores](docs/diagramas/C2-Contenedores.svg)

| Módulo | Responsabilidad | Puerto |
|---|---|---|
| `gateway` | Entrada pública única (Spring Cloud Gateway WebFlux), validación JWT en el borde, cabeceras de seguridad, CORS cerrado por defecto, límite de cuerpo (`413`), correlación y ocultamiento de `/internal/**` y `/actuator/prometheus`. | `8080` |
| `ms-animales` | Dueño del catálogo y de la máquina de estados del animal (`R-13`). Expone API pública y API interna idempotente (`/internal/animales/**`, rol `SERVICIO`). | `8081` (interno) |
| `ms-adopciones` | Perfiles, solicitudes, evaluación Georef, aprobación/entrega/control, eventos de dominio en proceso, reportes, reconciliador ADR-06 y tareas ShedLock. | `8082` (interno) |
| `ms-donaciones` | Preferencias Checkout Pro, `Idempotency-Key`, webhook público firmado (`x-signature` HMAC-SHA256), reconciliación ShedLock y consulta bajo demanda. | `8083` (interno) |
| `common` | Librería Maven compartida: `ErrorResponse`, `GlobalExceptionHandler`, manejadores JSON `401`/`403`, `JwtTokenValidators`, `KeycloakRoleConverter`, `ResourceServerDefaults` y correlación Servlet/`RestClient`. | — |
| `coverage-report` | Agregador JaCoCo multi-módulo ejecutado en la fase `verify`. | — |

| Tecnología | Versión fijada | Uso |
|---|---|---|
| Java | 21 (`--release 21`, Temurin `21.0.13.1_2-jre-noble`) | Virtual threads en microservicios MVC; records e inmutabilidad |
| Spring Boot / Cloud | `3.5.16` / `2025.0.3` | WebMVC, WebFlux Gateway, OAuth2 Resource Server, Actuator, Validation |
| PostgreSQL + Flyway | `17-alpine` + Flyway | Instancia de negocio aislada en 3 esquemas/usuarios + instancia dedicada de Keycloak |
| Keycloak | `26.8.1` | OIDC / JWT RS256 (`adopciones-pkce`, `adopciones-service`, `adopciones-cli` demo) |
| Resilience4j + ShedLock | Gestionado por Cloud BOM + `6.3.0` | Circuit breakers, retries solo en `GET` y locks distribuidos sobre JDBC |

---

## 4. Decisiones de ingeniería destacadas

Las **27 decisiones arquitectónicas (ADR-01 a ADR-27)** están indexadas y justificadas en [`docs/ADR.md`](docs/ADR.md):

| Área | Decisión implementada | Referencia |
|---|---|---|
| **Defensa en profundidad JWT** | Doble validación (Gateway + microservicios) con *clock skew* explícito (`30s`), allowlist de clientes (`azp`), audiencia opcional (`aud`) y token `client_credentials` (`ROLE_SERVICIO`) precalentado fuera de transacción (`ReentrantLock` + `volatile`). | [ADR-04](docs/ADR.md#adr-04-seguridad-en-dos-capas-y-token-de-servicio-trusted-zone), [ADR-15](docs/ADR.md#adr-15-renovación-de-token-de-servicio-con-reentrantlock-y-precalentamiento-fuera-de-la-transacción), [ADR-22](docs/ADR.md#adr-22-endurecimiento-de-jwt-azpaudclock-skew-probes-actuator-y-escaneo-continuo-ws-5) |
| **Consistencia entre servicios** | `saveAndFlush` local previo al `PUT` interno idempotente (`solicitudId`) con *rollback* automático ante `409`/`503`, complementado por `ReconciliadorAdopciones` para la ventana residual post-200. | [ADR-06](docs/ADR.md#adr-06-consistencia-entre-servicios-sin-transacción-distribuida), [ADR-17](docs/ADR.md#adr-17-resolución-de-la-inconsistencia-residual-del-adr-06-estado-actual-vs-outbox-vs-saga-con-compensación), [ADR-20](docs/ADR.md#adr-20-virtual-threads-reintentos-selectivos-en-get-idempotentes-transiciones-idempotentes-en-animales-y-shedlock-jdbc), [ADR-21](docs/ADR.md#adr-21-cierre-de-brechas-operativas-y-hardening-del-gateway-ws-4) |
| **Concurrencia e idempotencia** | `@Version` optimista + columnas `UNIQUE` condicionales (`animal_pendiente`, `adoptante_activo`, probado con hilos concurrentes: `1×200 + 1×409`) e `Idempotency-Key` con hash SHA-256 en donaciones. | [ADR-07](docs/ADR.md#adr-07-concurrencia-columnas-de-bloqueo-con-unique--bloqueo-optimista), [ADR-18](docs/ADR.md#adr-18-idempotencia-opcional-en-post-apidonaciones-con-idempotency-key-y-conflicto-409) |
| **Resiliencia externa** | Timeouts de conexión/lectura separados, `CircuitBreaker` que ignora errores de negocio (`404`/`409`) y `Retry` únicamente sobre consultas `GET` idempotentes (nunca sobre `POST` de cobros). | [ADR-16](docs/ADR.md#adr-16-timeouts-diferenciados-y-circuit-breaker-resilience4j-en-clientes-http), [ADR-20](docs/ADR.md#adr-20-virtual-threads-reintentos-selectivos-en-get-idempotentes-transiciones-idempotentes-en-animales-y-shedlock-jdbc) |
| **Evolución de contratos** | Esquemas versionados con Flyway, paginación SQL aditiva (`page`, `size`, `X-Total-Count`) y contratos OpenAPI 3 exportados y verificados en `docs/openapi/*.json`. | [ADR-19](docs/ADR.md#adr-19-política-de-versionado-de-la-api-rest-api), [ADR-26](docs/ADR.md#adr-26--migraciones-flyway-paginación-aditiva-y-protección-interna-de-prometheus) |

---

## 5. Seguridad, observabilidad y rendimiento

- **Modelo de amenazas STRIDE y escaneo continuo ([`docs/SEGURIDAD.md`](docs/SEGURIDAD.md)):** `.github/workflows/security.yml` ejecuta `CodeQL`, `gitleaks` sobre historial completo, `Trivy` sobre filesystem/IaC y sobre las **4 imágenes Docker**, y `dependency-review` en cada PR. Contenedores no root (`USER app`) con imágenes base fijadas y límites de CPU/memoria.
- **Observabilidad y apagado ordenado ([`docs/OPERACION.md`](docs/OPERACION.md)):** probes `liveness`/`readiness` (`show-details: never`), `server.shutdown: graceful` (`20s`, `stop_grace_period: 30s`), logs estructurados con `X-Correlation-ID` y `/actuator/prometheus` interno protegido con `ROLE_SERVICIO` exponiendo contadores de negocio y el gauge `adopciones_solicitudes_actuales{estado="..."}` (respaldado por una única consulta `GROUP BY` cacheada).
- **Rendimiento medido sobre PostgreSQL 17 ([`docs/rendimiento.md`](docs/rendimiento.md)):** `open-in-view: false` + `@BatchSize(50)` eliminan N+1 (`2` queries constantes en `SolicitudListarQueryCountTest`), e índices específicos logran mejoras medidas de **2.0x a 5.4x** y reducciones de **90–94%** en lectura de buffers sobre tablas de 10.000 filas (`EXPLAIN (ANALYZE, BUFFERS)`).

---

## 6. Calidad automatizada y cobertura medida

El proyecto incluye **Maven Wrapper** (`./mvnw` / `./mvnw.cmd`, Maven `3.9.16`) y ejecuta todas las compuertas estáticas en `./mvnw clean verify` ([ADR-23](docs/ADR.md#adr-23-compuertas-automatizadas-de-calidad-arquitectura-y-cobertura-ws-6)):
- **Maven Enforcer:** Java `[21,22)`, Maven `>= 3.9.0` y `dependencyConvergence`.
- **Checkstyle (`0` violaciones) + SpotBugs (`effort=Max`, `0` bugs)** + **ArchUnit (`18` reglas de arquitectura, prohibición de Lombok y `@Value`)**.
- **JaCoCo gates bloqueantes:** `>= 80%` líneas en paquetes `*.model` y `*.service`, y `>= 85%` en `common`.

```bash
# Suite rápida de unidad y arquitectura (@Tag("unit") / @ArchTag("unit"), ~1 min local)
./mvnw -B test -Dgroups=unit

# Verificación completa del reactor + gates estáticos + reporte agregado JaCoCo (~2 min en CI)
./mvnw -B clean verify

# Pruebas de integración reales contra PostgreSQL 17 con Testcontainers + Flyway (~2m 15s en CI)
./mvnw -B verify -Pintegration-tests
```

### Cobertura agregada verificada (`coverage-report/target/site/jacoco-aggregate/jacoco.csv`)

| Módulo | Tests (`verify` + `*IT`) | Líneas cubiertas | Ramas cubiertas |
|---|---:|---:|---:|
| `common` | 37 | 217 / 217 (**100.0%**) | 84 / 95 (**88.4%**) |
| `ms-animales` | 49 + 1 IT | 208 / 223 (**93.3%**) | 62 / 68 (**91.2%**) |
| `ms-adopciones` | 93 + 1 IT | 848 / 936 (**90.6%**) | 200 / 266 (**75.2%**) |
| `ms-donaciones` | 38 + 1 IT | 397 / 460 (**86.3%**) | 112 / 176 (**63.6%**) |
| `gateway` | 20 | 170 / 207 (**82.1%**) | 50 / 100 (**50.0%**) |
| **Total reactor** | **237 + 3 IT (+ E2E)** | **1.840 / 2.043 (90.1%)** | **508 / 705 (72.1%)** |

---

## 7. Trade-offs y limitaciones conocidas

- **Sin broker de mensajería externo:** para mantener una huella operativa acotada, las transiciones entre `ms-adopciones` y `ms-animales` usan coordinación síncrona idempotente más reconciliación periódica (`ReconciliadorAdopciones`) en lugar de Kafka/RabbitMQ + Outbox (diseño evolutivo documentado en [ADR-17](docs/ADR.md#adr-17-resolución-de-la-inconsistencia-residual-del-adr-06-estado-actual-vs-outbox-vs-saga-con-compensación)).
- **Distancia en línea recta (Haversine):** `R-06` evalúa distancia geodésica sobre coordenadas de Georef en lugar de ruteo vial pago.
- **Entorno local vs. borde productivo:** `docker-compose.yml` ejecuta Keycloak con `start-dev` (HTTP local) e incluye el cliente `adopciones-cli` (*password grant*) junto al cliente `adopciones-pkce` (*PKCE S256*) para facilitar pruebas automatizadas y Postman. El checklist de endurecimiento previo a producción (TLS, desactivación de `adopciones-cli`, `JWT_REQUIRED_AUDIENCE` y *rate limiting* en el ingress) está detallado en [`docs/SEGURIDAD.md`](docs/SEGURIDAD.md#5-checklist-obligatorio-antes-de-producción-keycloak-y-entorno).

---

## 8. Índice de documentación

- [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) — Topología, fronteras de red, diagramas de secuencia y CI/CD.
- [`docs/API.md`](docs/API.md) y [`docs/openapi/`](docs/openapi) — Referencia completa de endpoints, códigos de error, webhooks y especificaciones OpenAPI 3.
- [`docs/ADR.md`](docs/ADR.md) — Registro indexado de las 27 decisiones arquitectónicas (ADR-01 a ADR-27).
- [`docs/SEGURIDAD.md`](docs/SEGURIDAD.md) y [`SECURITY.md`](SECURITY.md) — Modelo de amenazas STRIDE, validación JWT, gestión VEX/CVEs y reporte de vulnerabilidades.
- [`docs/OPERACION.md`](docs/OPERACION.md) — Runbook de despliegue, probes, Prometheus, `X-Correlation-ID`, ShedLock, backups PostgreSQL y rotación de secretos.
- [`docs/rendimiento.md`](docs/rendimiento.md) — Eliminación de N+1 y benchmark `EXPLAIN (ANALYZE, BUFFERS)` con 10.000 filas en PostgreSQL 17.
- [`docs/PATRONES.md`](docs/PATRONES.md), [`docs/ANALISIS.md`](docs/ANALISIS.md) y [`docs/CONTRATOS.md`](docs/CONTRATOS.md) — Patrones aplicados, análisis de dominio y contratos.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) y [`CHANGELOG.md`](CHANGELOG.md) — Guía de contribución, gates de calidad e historial de versiones.
- [`postman/`](postman) — Colección y entorno Postman listos para importar.

---

## Licencia

Distribuido bajo licencia **MIT**. Ver [`LICENSE`](LICENSE).
