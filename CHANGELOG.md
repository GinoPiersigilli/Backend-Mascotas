# Changelog

Todos los cambios relevantes de este proyecto se documentan en este archivo siguiendo [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y [Semantic Versioning](https://semver.org/lang/es/).

## [Unreleased]

### Added
- **API & Dominio:**
  - Encabezado opcional `Idempotency-Key` en `POST /api/donaciones` con hash SHA-256 por donante y respuesta `409 Conflict` ante discrepancia de monto (ADR-18).
  - Webhook público firmado `POST /api/donaciones/webhooks/mercadopago` con validación HMAC-SHA256 en tiempo constante (`x-signature`) y consulta autoritativa `GET /v1/payments/{id}` (ADR-21).
  - Endpoints de gestión para `COORDINADOR`: `GET /api/solicitudes/reportes`, `POST /api/solicitudes/reconciliacion` y filtro `?controlVencido=true` (ADR-21).
  - Paginación SQL aditiva (`page`, `size`, cabecera `X-Total-Count`) en listados de animales, solicitudes y donaciones (ADR-19, ADR-26).
  - Eventos de dominio en proceso post-commit (`SolicitudAprobadaEvent`, `EntregaConfirmadaEvent`, `AdopcionConfirmadaEvent`).
  - Especificaciones OpenAPI 3 exportadas y verificadas en `docs/openapi/*.json`.
- **Concurrencia, Datos y Resiliencia:**
  - Migraciones versionadas con Flyway (`V1..V3`) en los tres servicios, `ddl-auto: validate`, índices compuestos y benchmark medido sobre 10.000 filas en PostgreSQL 17 (`docs/rendimiento.md`).
  - Virtual threads (`spring.threads.virtual.enabled: true`), `ServiceTokenProvider` libre de `synchronized` (`volatile` + `ReentrantLock` + prewarm), transiciones internas idempotentes por `solicitudId` y bloqueo distribuido **ShedLock** sobre JDBC (ADR-15, ADR-20).
  - Circuit breakers (Resilience4j) con timeouts diferenciados y reintentos automáticos restringidos exclusivamente a consultas `GET` idempotentes (ADR-16, ADR-20).
  - Perfil reproducible sin credenciales externas (`docker-compose.demo.yml` + stubs WireMock) y suite E2E `scripts/prueba-humo.sh`.
- **Seguridad:**
  - Validación estricta y perezosa de JWT con *clock skew* de `30s`, lista blanca de clientes (`azp`), audiencia opcional (`aud`) y cliente público `adopciones-pkce` (*PKCE S256*) en Keycloak (ADR-22).
  - Hardening del API Gateway: CORS cerrado por defecto, cabeceras de seguridad, límite de tamaño de cuerpo (`413` JSON) y sanitización anti-CRLF de `X-Correlation-ID`.
  - Workflow `.github/workflows/security.yml` (`CodeQL`, `gitleaks`, `Trivy` FS e imágenes Docker, `dependency-review`), `SECURITY.md` y modelo de amenazas STRIDE (`docs/SEGURIDAD.md`).
- **Observabilidad, Calidad y CI/CD:**
  - Probes `liveness`/`readiness` (`show-details: never`), *graceful shutdown* (`20s` / `stop_grace_period: 30s`), métricas de negocio en `/actuator/prometheus` (protegido con `ROLE_SERVICIO`) y runbook `docs/OPERACION.md` (ADR-24).
  - Maven Wrapper (`3.9.16`), `maven-enforcer-plugin`, Checkstyle, SpotBugs, ArchUnit (`18` reglas), módulo agregador `coverage-report` (**90.1%** líneas sobre 237 tests + 3 ITs Testcontainers) (ADR-23).
  - Imágenes Docker fijadas con etiquetas OCI, límites de CPU/memoria en Compose y pipeline `.github/workflows/ci.yml` con jobs `verify`, `integration-tests`, `e2e-demo` y publicación automática en GHCR + GitHub Release en tags `v*` (ADR-25).
  - Documentación de arquitectura y gobernanza OSS (`docs/ARQUITECTURA.md`, `CONTRIBUTING.md`, plantillas de Issues y Pull Requests).

### Changed
- Actualización de dependencias de seguridad sobre Spring Boot `3.5.16` (`postgresql 42.7.12`, `jackson 2.21.9`, `tomcat 10.1.62`, `netty 4.1.141.Final`, `bouncycastle 1.87`).

### Removed
- Eliminación de documentos de auditoría académica preliminar y referencias obsoletas a `ddl-auto: update`.
