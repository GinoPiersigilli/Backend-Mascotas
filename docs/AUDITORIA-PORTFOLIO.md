# Auditoría Técnica de Portfolio — `tpi-backend-2026`

- **Fecha de auditoría:** 2026-11-27
- **Commit auditado (`main`):** `6b006d1` (PR #12 mergeado)
- **Entorno detectado localmente:** Maven 3.9.16, JDK 21.0.12 (y JRE 1.8 en `PATH`, Maven usa JDK 21), Docker 29.9.3
- **Evaluador:** Staff Backend Engineer (criterio de contratación junior/semi-senior, rúbrica de 100 puntos)
- **Alcance:** Fase 1 (solo lectura del código, configuración, Docker, CI, infra, scripts, Postman, docs e historial git; **sin cambios de código**).

---

## 0. Discrepancias críticas frente al “Estado de partida” esperado (Regla 8)

El estado real de `main` (`6b006d1`) **difiere sustancialmente** de la línea base descripta en el brief inicial porque ya se mergearon 12 PRs de mejora sobre el commit inicial (`7b4c66b`):

| Aspecto | Descripción del brief (“Estado de partida”) | Estado real en `main` (`6b006d1`) | Evidencia |
|---|---|---|---|
| Pruebas automatizadas | ~128 tests en H2 | **157 tests en verde** (H2/unit/MockMvc/HttpServer) + **3 clases `*IT`** (Testcontainers PostgreSQL 17, perfil `-Pintegration-tests`) | Última ejecución registrada + `*MetricasTest`, `*PaginacionTest`, `ClientesResilienciaTest`, `MercadoPagoResilienciaTest`, `FlywayMigrationIT` |
| Persistencia | `ddl-auto: update`, sin migraciones ni índices, N+1 | **Flyway `V1__esquema_inicial.sql`** por servicio, `ddl-auto: validate`, `open-in-view: false`, índices declarados, `@BatchSize(size = 50)`, `SolicitudListarQueryCountTest` | `ms-*/src/main/resources/db/migration/V1__esquema_inicial.sql`, `application.yml`, `Solicitud.java:47-51,90` |
| CI/CD | Solo `.gitlab-ci.yml` | `.gitlab-ci.yml` **ya fue eliminado** (`865189e`); existe **`.github/workflows/ci.yml`** con `mvn -B verify` y upload de JaCoCo | `.github/workflows/ci.yml:1-34` |
| Licencia y README | Sin licencia; README con tono de TPI y Docker no verificado | Existe **`LICENSE` (MIT)** y `README.md` ya fue reescrito con enfoque portfolio y sin la limitación de Docker | `LICENSE`, `README.md:1-240` |
| Material de defensa | Presente en el repo | `docs/DEFENSA.md`, `PREGUNTAS.md`, `GUIAS-ESTUDIO.md`, `GITLAB.md` y `.docx` **ya fueron borrados de `HEAD`** (persisten en el historial `7b4c66b`). Siguen en disco local (ignorados por git) `README-CLASES.md` y `RESUMEN-EXPOSICION.md`. `.github/modernize/` **no existe**. | `git log --all --name-only`, `.gitignore:10-11` |
| Mejoras técnicas ya presentes en `main` | Pendientes en WS-1..WS-9 | Ya implementados: paginación con `X-Total-Count` (PR #4), remoción de `@DirtiesContext` + JaCoCo (PR #5), `ServiceTokenProvider` con `volatile` + `ReentrantLock` + precalentamiento (PR #6), `Clock` en `ms-animales` (PR #7), `X-Correlation-ID` en Gateway WebFlux (PR #8), Dockerfiles BuildKit + `USER app` no-root + `SWAGGER_ENABLED=false` (PR #9), timeouts separados + Resilience4j Circuit Breaker (PR #10), ADR-17 Outbox vs Saga (PR #11), métricas Prometheus protegidas con `ROLE_SERVICIO` + 2 contadores de negocio (PR #12). | `git log --oneline -20 origin/main` |

**Implicancia:** varios sub-ítems de WS-1, WS-2, WS-3, WS-7, WS-8 y WS-9 ya están resueltos o parcialmente resueltos, pero el repositorio **todavía está en 69/100** por brechas concretas de calidad automatizada, seguridad avanzada, idempotencia de pagos, operabilidad (`graceful shutdown`, liveness/readiness, runbook), CI incompleto (sin Testcontainers ni escaneos de seguridad ni E2E), contradicciones en docs y falta de demo offline con WireMock.

---

## a) Scorecard actual (rúbrica de 100 puntos)

| Categoría | Puntaje | Máx. | Justificación y evidencia (`archivo:línea`) |
|---|---:|---:|---|
| 1. Arquitectura y diseño de dominio | **12.5** | 15 | **Bien:** 3 servicios con límites de datos nítidos (`Animal`, `Solicitud`, `Donacion`); modelos ricos sin setters públicos ni Lombok (`Animal.java:129-174`, `Solicitud.java:169-270`, `Donacion.java:95-112`); DTOs `record` en toda la API (0 entidades JPA serializadas); 17 ADRs (`docs/ADR.md`). **Falta:** persiste la llamada HTTP dentro de `tx.execute` (`SolicitudService.java:212-223,266-273`) y la inconsistencia residual de ADR-06; `Animal.cambiarEstadoPorProceso` (`Animal.java:129-136`) no es idempotente; faltan automatismo de control final vencido (R-11) y conciliación de donaciones pendientes; sin tests `ArchUnit` que impongan los límites en build. |
| 2. Calidad de código | **7.5** | 10 | **Bien:** cero `@Value` dispersos, todas las configs son `@Validated @ConfigurationProperties` records (`PoliticaAdopcion.java:15-24`, `IntegracionesProperties.java:17-54`, `DonacionesProperties.java:19-42`); cero `synchronized` con I/O (`ServiceTokenProvider.java:46-75`); manejo centralizado en `GlobalExceptionHandler.java`. **Falta:** sin Checkstyle/Spotless, SpotBugs ni `maven-enforcer-plugin`; `Duration` en properties solo tiene `@NotNull` (permite `0s` o negativos); `DatosDemo.java:40-51` no inyecta `Clock`; métodos `listar(...)` sin paginar remanentes (`AnimalService.java:49`, `SolicitudService.java:129`, `DonacionService.java:72`); propiedad `adopciones.tareas.demora-inicial-ms` usada en `TareaVencimientos.java:32` sin declararse en `application.yml`; duplicación menor del record `Circuito` entre adopciones y donaciones. |
| 3. Persistencia y datos | **7.5** | 10 | **Bien:** Flyway `V1__esquema_inicial.sql` en los 3 servicios + `ddl-auto: validate` + `open-in-view: false`; índices creados sin duplicar las `UNIQUE`; `@BatchSize(size = 50)` en `Solicitud.observaciones` (`Solicitud.java:90`) probado en `SolicitudListarQueryCountTest.java:45-60` (2 queries para N=5 y N=25); `@Version` + `OptimisticLockingFailureException -> 409` (`GlobalExceptionHandler.java:91-95`). **Falta:** HTTP dentro de transacción JDBC retiene conexión HikariCP (`SolicitudService.java:266-273`); pool HikariCP sin configurar explícitamente (`spring.datasource.hikari.*` ausente); `hibernate.default_batch_fetch_size` ausente; `Perfil.java` sin `@Version`; `docs/rendimiento.md:165-174` con resultados `EXPLAIN ANALYZE` en `TODO`; **`FlywayMigrationIT` no se ejecuta en CI** (`.github/workflows/ci.yml:27`). |
| 4. Seguridad | **8.5** | 12 | **Bien:** defensa en profundidad Gateway + 3 servicios; `/internal/**` y `/actuator/prometheus` protegidos con `ROLE_SERVICIO` solo en red interna; `SWAGGER_ENABLED=false` en `docker-compose.yml`; 0 PII en `log.*` auditados (`GeorefClient.java:69`, `ServiceTokenProvider.java:103`, `MercadoPagoClient.java:137`); 0 secretos reales en `git log --all`; contenedores `USER app` no-root. **Falta:** JWT no valida audiencia (`aud`/`azp`) (`ResourceServerDefaults.java:26-29`, `gateway/.../SecurityConfig.java:55-58`); tests negativos de IDOR/RBAC incompletos (faltan `GET /api/solicitudes/{id}/historial` y `POST /api/solicitudes/{id}/devolucion` con otro adoptante, y pruebas negativas de rol en varios `POST`/`PUT`); Gateway sin CORS configurable, cabeceras de seguridad ni límite de body; sin Dependabot, CodeQL, Trivy ni gitleaks en CI; sin `docs/SEGURIDAD.md` ni `SECURITY.md`. |
| 5. Testing | **10.5** | 15 | **Bien:** 157 tests en verde + 3 `*IT`; sin `@DirtiesContext` en flujos principales; JaCoCo medido (`ms-adopciones` 95.1% líneas / 82.2% branches, `ms-donaciones` 91.0% / 61.1%, `ms-animales` 88.9% / 62.5%, `gateway` 77.4% / 47.3%); concurrencia en `ServiceTokenProviderTest.java:127-177`; resiliencia con `HttpServer` real (`ClientesResilienciaTest`, `MercadoPagoResilienciaTest`). **Falta:** **0 tests de concurrencia en aprobaciones simultáneas** (y `GlobalExceptionHandler.handleConcurrencia` nunca se testea); baja cobertura en **`tpi-common` (57.2% líneas / 50.8% branches)**; sin `jacoco:check` (umbral) ni reporte agregado; `*IT` fuera del CI; sin `@Tag("unit")`/`@Tag("e2e")` ni E2E en CI; `Thread.sleep` ajustados en tests de resiliencia (`ClientesResilienciaTest.java:66,102`) con riesgo de flakiness en runners lentos. |
| 6. API y contratos | **5.0** | 8 | **Bien:** `ErrorResponse` uniforme incluyendo `HandlerMethodValidationException` y `ConstraintViolationException`; paginación retrocompatible con `X-Total-Count` en los 3 listados; 0 entidades JPA serializadas; OpenAPI con esquema `bearer-jwt`. **Falta:** **sin `Idempotency-Key` en `POST /api/donaciones`** (`DonacionController.java:46-56`, `DonacionService.java:60-69` — cobros duplicados ante reintentos); anotaciones `@ApiResponse` incompletas (faltan 400/401/403/404/409/503 y ejemplos en la mayoría de endpoints); sin exportación de `docs/openapi/*.json` desde el build; sin ADR de política de versionado. |
| 7. Operabilidad y observabilidad | **4.5** | 8 | **Bien:** correlación `X-Correlation-ID` en logs de los 4 servicios (incluyendo WebFlux con `context-propagation`); `/actuator/prometheus` interno + 2 contadores de negocio (`SolicitudService.java:77-82`); healthchecks no-root y rotación de logs en compose. **Falta:** **sin `server.shutdown: graceful`** ni `timeout-per-shutdown-phase` en ningún `application.yml`; **sin probes liveness/readiness** ni `show-details: never` explícito; `spring.threads.virtual.enabled` **no activado** (pese a ADR-15); `TareaVencimientos` sin `ShedLock` (ejecución duplicada en réplicas) ni métricas de procesadas/fallidas; sin métricas de negocio en `ms-donaciones`; sin retry en operaciones idempotentes (`GeorefClient`, `MercadoPagoClient.buscarPagoAprobado`); sin runbook `docs/OPERACION.md`. |
| 8. DevEx, CI/CD y cadena de suministro | **6.5** | 12 | **Bien:** `.github/workflows/ci.yml` funcional; Dockerfiles con BuildKit cache, `dependency:go-offline`, `USER app`, `MaxRAMPercentage=75`; `docker-compose.yml` con `service_healthy` y red interna. **Falta:** **sin perfil demo offline (WireMock)** para Georef y Mercado Pago; sin Maven Wrapper (`mvnw`); CI sin `concurrency`, sin job de Testcontainers, sin E2E, sin CodeQL/Trivy/gitleaks/dependency-review, sin publicación GHCR ni releases; sin Dependabot; Dockerfiles sin labels OCI ni imágenes base fijadas por versión/digest; compose sin límites de memoria/CPU. |
| 9. Documentación y presentación | **6.5** | 10 | **Bien:** `README.md` de portfolio con C1/C2 SVG, `LICENSE` MIT, 17 ADRs, `CONTRATOS.md`, `API.md`, `rendimiento.md`. **Falta:** **contradicciones verificables en docs** (`README.md:95` dice "14 ADRs" cuando hay 17; `docs/PATRONES.md:46` dice que Circuit Breaker Resilience4j *no se usa*; `docs/ADR.md:67` dice `ddl-auto: update`; `docs/AUDITORIA.md` conserva texto académico viejo con "128 tests", "JDK 27", "Integrante 1..5", "Docker NV"); faltan resumen en inglés en README, tiempos/cobertura medidos, `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`, plantillas de issue/PR, `docs/ARQUITECTURA.md`, `docs/SEGURIDAD.md`, `docs/OPERACION.md`; metadata de GitHub (descripción/topics/tag `v1.0.0`) **NO VERIFICABLE** localmente. |
| **TOTAL** | **69.0** | **100** | **Nivel actual: Junior+/Semi-Senior sólido, pero lejos de 90–95/100.** Faltan garantías de producción (idempotencia en pagos, graceful shutdown, liveness/readiness, CI con Testcontainers/E2E/seguridad, calidad automatizada, consistencia documental y demo offline ejecutable). |

---

## b) Hallazgos priorizados

| ID | Severidad | Hallazgo y evidencia (`archivo:línea`) | Impacto en entrevista técnica | Esfuerzo | Workstream |
|---|---|---|---|---|---|
| H-01 | **Bloqueante** | **Sin `Idempotency-Key` en `POST /api/donaciones`** (`DonacionController.java:46-56`, `DonacionService.java:60-69`). Un reintento tras timeout genera una segunda preferencia de pago en Mercado Pago y una segunda fila `donacion`. | Pregunta clásica de entrevistas backend en flujos con dinero: “¿qué pasa si el cliente reintenta un `POST` tras un timeout?”. Sin idempotencia, es un rechazo fuerte. | M | WS-2 |
| H-02 | **Bloqueante** | **Sin validación de migraciones Flyway en CI y sin perfil demo offline con WireMock** (`.github/workflows/ci.yml:27` no ejecuta `-Pintegration-tests`; `docker-compose.yml` depende de Internet/Georef y `MP_ACCESS_TOKEN`). | Un entrevistador que clona el repo o mira el Actions verde descubre que las migraciones reales nunca se probaron en CI y que P5 falla con `503` sin credenciales propias. | M | WS-0, WS-1, WS-9 |
| H-03 | **Bloqueante** | **Contradicciones directas entre documentación y código actual:** `docs/PATRONES.md:46` afirma que *no* se usa Circuit Breaker (Resilience4j) cuando sí está en `ms-adopciones`/`ms-donaciones`; `docs/ADR.md:67` (ADR-08) menciona `ddl-auto: update`; `README.md:95` dice "14 ADRs" (hay 17); `docs/AUDITORIA.md` conserva texto académico ("128 tests", "Integrante 1..5", "equipo docente", "Docker NV"). | Destruye credibilidad en 30 segundos: parece que la documentación no fue mantenida por el autor o fue copiada sin revisar. | S | WS-10 |
| H-04 | **Alta** | **Falta `server.shutdown: graceful` y probes `liveness`/`readiness`** en los 4 `application.yml` (`gateway`, `ms-animales`, `ms-adopciones`, `ms-donaciones`). | En contenedores/Kubernetes, un `SIGTERM` corta transacciones activas (agravando incluso la ventana de ADR-06) y el orquestador no distingue arranque de fallo. | S | WS-5, WS-8 |
| H-05 | **Alta** | **Llamada HTTP dentro de transacción JDBC y transiciones de `ms-animales` no idempotentes** (`SolicitudService.java:212-223,266-273`; `Animal.java:129-136`). Retiene conexión del pool HikariCP durante red y ante timeout tras commit remoto, `RESERVADO -> RESERVADO` responde `409`. | Tema central de diseño distribuido: explica el riesgo real de agotamiento del pool y por qué no se pueden reintentar transiciones de forma segura. | M | WS-3, WS-4 |
| H-06 | **Alta** | **Sin test de concurrencia para aprobaciones simultáneas y sin cobertura en `GlobalExceptionHandler.handleConcurrencia`** (`GlobalExceptionHandler.java:91-95`; `tpi-common` con solo 57.2% de líneas cubiertas). | El README destaca `@Version` para concurrencia, pero no hay ningún test que demuestre que dos aprobaciones paralelas terminan en 1 ganador (`200`) y 1 conflicto (`409`). | S | WS-1, WS-7 |
| H-07 | **Alta** | **Sin escaneos de seguridad ni supply-chain en CI** (faltan Dependabot, CodeQL, Trivy, gitleaks, `dependency-review-action`) y sin `concurrency` en `.github/workflows/ci.yml`. | En equipos reales de ingeniería, un repo de portfolio sin seguridad automatizada en CI baja fuerte el puntaje de madurez operativa. | M | WS-5, WS-9 |
| H-08 | **Alta** | **Sin puertas de calidad automatizadas** (Spotless/Checkstyle, SpotBugs, `maven-enforcer`, ArchUnit, umbral `jacoco:check`, Maven Wrapper `mvnw`). | Sin *build gates*, la calidad depende de disciplina manual y cualquier PR puede degradar cobertura o acoplar capas. | M | WS-6 |
| H-09 | **Alta** | **Tests negativos IDOR/RBAC incompletos:** faltan pruebas con otro adoptante sobre `GET /api/solicitudes/{id}/historial` y `POST /api/solicitudes/{id}/devolucion` (`FlujoAdopcionIntegrationTest.java:394-415`) y matriz negativa completa por rol en endpoints `POST`/`PUT` de animales y solicitudes. | En auditorías de seguridad, un endpoint con pertenencia sin test negativo dedicado es un punto ciego de regresión IDOR. | S | WS-5 |
| H-10 | **Alta** | **`TareaVencimientos` sin lock distribuido (`ShedLock`) ni métricas** (`TareaVencimientos.java:32-49`). Con 2+ réplicas de `ms-adopciones`, ambas ejecutan simultáneamente `buscarEntregasVencidas()` y compiten sobre las mismas solicitudes/animales. | Pregunta inevitable de escalado horizontal: “¿qué pasa si corrés 2 instancias de `ms-adopciones`?”. | S | WS-3, WS-8 |
| H-11 | **Media** | **Gateway sin hardening de borde:** sin CORS configurable, sin cabeceras de seguridad (`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, etc.) y sin límite de tamaño de payload (`gateway/.../SecurityConfig.java`, `gateway/application.yml`). | El Gateway es la única frontera pública; dejar cabeceras y límites de body al default expone superficie innecesaria. | S | WS-4 |
| H-12 | **Media** | **JWT sin validación de audiencia (`aud`) / `azp` ni documentación explícita de `clock skew`** (`ResourceServerDefaults.java:26-29`, `gateway/.../SecurityConfig.java:55-58`). | Un token válido del mismo realm emitido para otro cliente podría ser aceptado si tiene los mismos realm roles. | S | WS-5 |
| H-13 | **Media** | **OpenAPI incompleto y sin artefactos `docs/openapi/*.json`:** faltan `@ApiResponse` (400/401/403/404/409/503) y ejemplos en controladores (`AnimalController.java`, `SolicitudController.java`, `PerfilController.java`, `DonacionController.java`), y falta ADR de política de versionado. | Un consumidor de API o entrevistador espera contratos OpenAPI completos y versionables fuera de tiempo de ejecución. | M | WS-2 |
| H-14 | **Media** | **Funcionalidades declaradas como limitación aún pendientes:** control final vencido R-11 (`EN_ADAPTACION` con `fechaControl` vencida), conciliación programada de donaciones `PENDIENTE` antiguas y reconciliador del ADR-06. | Cerrarlas (o al menos F1+F2+F3) convierte limitaciones de TPI en diseño de producción completo. | L | WS-4 |
| H-15 | **Media** | **`spring.threads.virtual.enabled` inactivo y pool HikariCP sin parametrizar** (`ms-*/src/main/resources/application.yml`). ADR-15 diseñó `ServiceTokenProvider` para virtual threads de Java 21, pero no están habilitados. | Detalle que un revisor Java 21 nota de inmediato: se justifica `ReentrantLock` por virtual threads que luego no se activan. | S | WS-1, WS-3 |
| H-16 | **Media** | **Sin retry en operaciones GET idempotentes** (`GeorefClient.java:55-80`, `MercadoPagoClient.java:97-122`). Solo existe circuit breaker; un micro-corte transitorio falla al primer intento. | Contrastar “retry solo en GET idempotentes, nunca en `POST /checkout/preferences` sin clave” suma mucho en entrevista. | S | WS-3 |
| H-17 | **Media** | **`docs/rendimiento.md` con tabla de `EXPLAIN ANALYZE` en `TODO`** (`docs/rendimiento.md:165-174`) y falta `hibernate.default_batch_fetch_size` / `@BatchSize` en `Solicitud.eventos` (`Solicitud.java:128-130`). | Un documento de rendimiento con puros `TODO` parece trabajo a medio terminar. | S | WS-1 |
| H-18 | **Media** | **Faltan documentos estándar de repositorio y runbook:** `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`, plantillas de issue/PR, `docs/OPERACION.md`, `docs/SEGURIDAD.md`, `docs/ARQUITECTURA.md`, resumen en inglés en `README.md`, tiempos y cobertura medidos en `README.md`. | Eleva de “buen código” a “proyecto profesional listo para equipo”. | M | WS-8, WS-10 |
| H-19 | **Baja** | **Validaciones y detalles menores de código:** `Duration` sin `@DurationMin`/positivo en `IntegracionesProperties` y `DonacionesProperties`; `DatosDemo.java:40-51` sin `Clock`; `Perfil.java` sin `@Version`; métodos `listar(...)` sin paginar remanentes (`AnimalService.java:49`, `DonacionService.java:72`); `adopciones.tareas.demora-inicial-ms` omitida en `application.yml`; `Thread.sleep` ajustados en `ClientesResilienciaTest`/`MercadoPagoResilienciaTest` con riesgo leve de flakiness en CI. | Pulido de ingeniería y eliminación de deuda menor. | S | WS-1, WS-3, WS-7 |
| H-20 | **Baja** | **Dockerfiles y Compose sin labels OCI, tags sin fijar a parche/digest, sin límites de recursos (`mem_limit`/`cpus`) y prefijo `tpi` en nombres** (`*/Dockerfile`, `docker-compose.yml`). | Mejora reproducibilidad y profesionalismo del despliegue; el renombrado `tpi` queda sujeto a aprobación (WS-11). | S | WS-9, WS-11 |

---

## c) Verificación de afirmaciones del README y reglas de negocio contra el código

### Reglas de negocio (R-01 a R-13 y D-01 a D-04)

| Regla | Clase / método que la implementa (`archivo:línea`) | Tests que la respaldan | Estado |
|---|---|---|---|
| **R-01** | `SolicitudService.crear` (`SolicitudService.java:92-98`), `Solicitud` (`uk_solicitud_animal_pendiente`, `Solicitud.java:45,293`) | `FlujoAdopcionIntegrationTest.r01UnAnimalConSolicitudPendienteNoRecibeOtra`, `r01UnAnimalNoDisponibleNoRecibeSolicitudes`, `SolicitudRepositoryTest.r01LaBaseImpideDosSolicitudesPendientesSobreElMismoAnimal`, `FlywayMigrationIT` | **OK** |
| **R-02** | `SolicitudService.crear` (`SolicitudService.java:87-89`), `Solicitud` (`uk_solicitud_adoptante_activo`, `Solicitud.java:46,294`) | `FlujoAdopcionIntegrationTest.r02UnaSegundaSolicitudActivaSeRechaza`, `SolicitudRepositoryTest.r02LaBaseImpideDosSolicitudesActivasDelMismoAdoptante` | **OK** |
| **R-03** | `SolicitudService.crear` (`SolicitudService.java:83-85`), `PerfilRequest` (`PerfilRequest.java:11-45`) | `FlujoAdopcionIntegrationTest.r03SinPerfilNoSePuedeSolicitar`, `validacionesDeEntrada` | **OK** |
| **R-04** | `EvaluadorSolicitud.incompatibilidadDura` (`EvaluadorSolicitud.java:41-52`), `Solicitud.rechazadaPorIncompatibilidad` (`Solicitud.java:160-166`) | `EvaluadorSolicitudTest.r04*` (3), `SolicitudTest.r04LaIncompatibilidadQuedaRegistradaComoRechazoSinBloquear`, `FlujoAdopcionIntegrationTest.r04IncompatibilidadQuedaRechazadaAutomaticamente` | **OK** |
| **R-05** | `EvaluadorSolicitud.observacionesDeCompatibilidad` (`EvaluadorSolicitud.java:55-61`) | `EvaluadorSolicitudTest.r05NecesitaExteriorYNoHayPatioGeneraObservacionSinBloquear`, `FlujoAdopcionIntegrationTest.r05YR06FallaDeGeorefNoImpideLaSolicitud` | **OK** |
| **R-06** | `EvaluadorSolicitud.evaluarDistancia` (`EvaluadorSolicitud.java:64-82`), `GeorefClient` (`GeorefClient.java:46-80`), `CalculadoraDistancia` | `EvaluadorSolicitudTest.r06*` (4), `GeorefClientTest` (6), `ClientesResilienciaTest.georefTimeoutAbreCircuitoDegradaADistanciaNoVerificadaYSeRecupera`, `CalculadoraDistanciaTest` (4), `FlujoAdopcionIntegrationTest.r05YR06*`, `r06YR08*` | **OK** |
| **R-07** | `Solicitud.verificarAprobable`/`aprobar` (`Solicitud.java:169-186`), `SolicitudService.aprobar`/`conCambioDeAnimal` (`SolicitudService.java:175-189,263-286`) | `SolicitudTest.r07SoloSeApruebaUnaSolicitudPendiente`, `FlujoAdopcionIntegrationTest.r07SiAnimalesRechazaLaReservaNoSeAprueba`, `siAnimalesNoRespondeAlAprobarLaSolicitudSiguePendiente`, `ClientesResilienciaTest.animalesTimeoutAbreCircuitoResponde503RapidoYSeRecupera` | **OK** *(sin test de concurrencia simultánea)* |
| **R-08** | `Solicitud.verificarAprobable`/`rechazar` (`Solicitud.java:171-174,189-194`), `SolicitudRequests.Motivo` (`SolicitudRequests.java:30-34`) | `SolicitudTest.r08AprobarConMarcaDeDistanciaExigeJustificacion`, `r07SoloSeApruebaUnaSolicitudPendiente`, `FlujoAdopcionIntegrationTest.r06YR08LejosDelRefugioExigeJustificacionParaAprobar`, `validacionesDeEntrada` | **OK** |
| **R-09** | `Solicitud.plazoEntregaVencido`/`vencerPlazoEntrega` (`Solicitud.java:204-207,229-235`), `SolicitudService.confirmarEntrega`/`vencerEntrega` (`SolicitudService.java:199-237`), `TareaVencimientos` (`TareaVencimientos.java:32-49`) | `SolicitudTest.r09*` (2), `SolicitudRepositoryTest.encuentraLasAprobadasAnterioresAlLimite`, `FlujoAdopcionIntegrationTest.r09LaTareaProgramadaCancelaLasEntregasVencidasYLiberaAlAnimal`, `r09ConfirmarFueraDePlazoCancelaEInforma409` | **OK** |
| **R-10** | `Solicitud.confirmarEntrega` (`Solicitud.java:210-218`) | `SolicitudTest.r10ConfirmarEntregaCalculaLaFechaDeControl`, `FlujoAdopcionIntegrationTest.procesoCompletoHastaLaAdopcionConfirmada` | **OK** |
| **R-11** | `Solicitud.registrarControl` (`Solicitud.java:238-262`), `SolicitudService.registrarControl` (`SolicitudService.java:248-252`) | `SolicitudTest.r11ElControlAnticipadoSeRechazaYElConformeFinaliza`, `FlujoAdopcionIntegrationTest.procesoCompletoHastaLaAdopcionConfirmada` | **OK** *(sin automatismo de control vencido, declarado en `README.md:235`)* |
| **R-12** | `Solicitud.registrarControl`/`devolver` (`Solicitud.java:257-270`), `SolicitudService.devolver` (`SolicitudService.java:255-260`) | `SolicitudTest.r12ControlDesfavorableRevierte`, `r12DevolucionSoloDuranteLaAdaptacion`, `FlujoAdopcionIntegrationTest.r12DevolucionDelAdoptanteRevierteYLiberaAlAnimal` | **OK** *(falta test HTTP de `DESFAVORABLE` y test IDOR de devolución ajena)* |
| **R-13** | `Animal.marcarNoDisponible` (`Animal.java:146-160`), `EstadoAnimal.tieneAdopcionEnCurso` (`EstadoAnimal.java:34-36`) | `AnimalTest.r13NoDisponibleSoloDesdeDisponible` (4 estados), `AnimalControllerSecurityTest.r13SeTraduceA409` | **OK** |
| **D-01** | `Donacion.validarMonto` (`Donacion.java:95-99`), `DonacionDtos.CrearDonacion` (`DonacionDtos.java:19-24`), `DonacionService.crear` (`DonacionService.java:61`) | `FlujoDonacionIntegrationTest.d01MontoMenorAlMinimoSeRechazaSinLlamarAlProveedor` | **OK** |
| **D-02** | `DonacionService.crear`/`obtener` (`DonacionService.java:60-69,92-112`), `MercadoPagoClient` (`MercadoPagoClient.java:56-122`) | `MercadoPagoClientTest` (7), `MercadoPagoResilienciaTest`, `FlujoDonacionIntegrationTest.donacionPendienteSeConfirma*`, `siElProveedorNoResponde*` (2) | **OK** *(con proveedor simulado; MP real **No verificable** sin `MP_ACCESS_TOKEN`)* |
| **D-03** | `Donacion.confirmar` (`Donacion.java:102-112`), `uk_donacion_pago_id` (`V1__esquema_inicial.sql:15`), `DonacionService.obtener`/`confirmar` (`DonacionService.java:97-99,115-130`) | `FlujoDonacionIntegrationTest.d03UnaDonacionConfirmadaNoVuelveAConsultarAlProveedor`, `d03UnMismoPagoNoConfirmaDosDonaciones` | **OK** |
| **D-04** | `ms-donaciones/.../SecurityConfig.java:29-34`, `DonacionService.listar`/`obtener` (`DonacionService.java:81-96`) | `FlujoDonacionIntegrationTest.d04CadaDonanteVeSoloLoSuyoYElCoordinadorVeTodo`, `DonacionPaginacionTest` | **OK** |

### Otras afirmaciones del README y documentación

| Afirmación (`archivo:línea`) | Verificación | Estado |
|---|---|---|
| *"Las 14 decisiones arquitectónicas completas están documentadas en docs/ADR.md"* (`README.md:95`) | `docs/ADR.md` tiene **17 ADRs** (ADR-01 a ADR-17). | **Falsa (desactualizada)** |
| *"Circuit Breaker (Resilience4j) — Evaluados y NO usados"* (`docs/PATRONES.md:46`) | Resilience4j Circuit Breaker **sí está implementado** en `GeorefClient`, `AnimalesClient` y `MercadoPagoClient` (ADR-16). | **Falsa** |
| *"`ddl-auto: update` simplifica pero no versiona el esquema (mejora: Flyway)"* (`docs/ADR.md:67`) | Ya usa Flyway + `ddl-auto: validate` en los 3 `application.yml`. | **Falsa (desactualizada)** |
| *"Docker Compose + `prueba-humo.sh` levantan el stack de 7 contenedores en estado healthy"* (`README.md:28-36`) | No ejecutado en esta pasada de auditoría solo lectura ni verificado en CI (requiere WS-0). | **No verificable** *(sin ejecución en CI / evidencia versionada)* |
| *"Reportes JaCoCo en `<módulo>/target/site/jacoco/index.html` y artifact `jacoco-reports`"* (`README.md:224`) | Verificado en `pom.xml:79-97` y `.github/workflows/ci.yml:29-34`; cobertura real medida localmente: `tpi-common` 57.2%, `ms-animales` 88.9%, `ms-adopciones` 95.1%, `ms-donaciones` 91.0%, `gateway` 77.4%. | **OK** |
| *"Actuator `/actuator/prometheus` interno con `ROLE_SERVICIO` y contadores de solicitudes"* (`README.md:144-152`) | Verificado en `SecurityConfig` x3, `SolicitudService.java:77-82,128,187`, `AdopcionesMetricasTest`, `AnimalesMetricasTest`, `DonacionesMetricasTest`, `GatewaySecurityTest`. | **OK** |

---

## d) Red flags de reclutador

| Red flag revisado | Estado actual | Evidencia |
|---|---|---|
| **Secretos en el historial git** | **Limpio (revisión manual regex); falta `gitleaks` en CI** | `git log --all -- .env` vacío; búsqueda de tokens `APP_USR-`, `AKIA`, `ghp_`, `PRIVATE KEY` en `git log --all -p` sin coincidencias; `.env.example` y `realm-adopciones.json` solo usan placeholders `${...}`. **Nota:** `gitleaks` no está instalado localmente (pendiente en CI, WS-5/WS-9). |
| **`ddl-auto: update` en producción** | **Resuelto** | Los 3 `application.yml` usan `ddl-auto: validate` + Flyway (`V1__esquema_inicial.sql`). Solo persiste el texto obsoleto en `docs/ADR.md:67`. |
| **Contenedores como `root`** | **Resuelto** | Los 4 `Dockerfile` crean `groupadd -r app && useradd -r -g app app` y ejecutan `USER app`. |
| **Ausencia de CI en GitHub** | **Parcialmente resuelto** | Existe `.github/workflows/ci.yml` (`mvn -B verify` + JaCoCo), pero **no** corre Testcontainers (`-Pintegration-tests`), E2E, CodeQL, Trivy ni gitleaks. |
| **Sin licencia** | **Resuelto** | Existe `LICENSE` (MIT). |
| **Tono de TPI / restos académicos** | **Pendiente parcial (Red flag activo)** | `README.md` ya tiene tono profesional, pero `docs/AUDITORIA.md` ("Integrante 1..5", "equipo docente", "128 ejecutadas", "JDK 27"), `docs/CONTRATOS.md` (13 menciones), `docs/ANALISIS.md` y `.gitignore:10-11` (`README-CLASES.md`, `RESUMEN-EXPOSICION.md` aún presentes en disco) delatan origen académico. Además los identificadores `tpi-common`, `tpi-backend-2026`, `tpi-client`, `tpi-net`, `postman/TPI-*` siguen presentes (WS-11 opcional). |
| **Afirmaciones no demostradas / contradictorias** | **Red flag activo** | `docs/PATRONES.md:46` contradice ADR-16; `docs/rendimiento.md:165-174` lleno de `TODO`; `README.md:26,243` con `<!-- TODO -->`; falta evidencia en CI de Docker Compose + Testcontainers. |
| **Tests dependientes del orden / flaky** | **Mayormente resuelto (riesgo leve)** | `@DirtiesContext` ya fue removido con limpieza en `@BeforeEach` y `reloj.reiniciar()`. Riesgo leve de flakiness por `Thread.sleep` cortos (`120ms`/`180ms`/`230ms`) en `ClientesResilienciaTest` y `MercadoPagoResilienciaTest`. |

### Revisión puntual de los puntos obligatorios de auditoría
- **N+1 y queries sin índice:** Resuelto en `SolicitudService.listar` (`@BatchSize(size = 50)`, 2 queries verificadas); índices presentes en las 3 migraciones V1. Pendiente: `hibernate.default_batch_fetch_size` y resultados reales en `docs/rendimiento.md`.
- **Llamadas HTTP dentro de transacciones:** **Sí existen** en `SolicitudService.conCambioDeAnimal` (`line 270`) y `vencerEntrega` (`line 220`); precalentamiento de token ya fuera (ADR-15), pero el `PUT` a `ms-animales` retiene conexión HikariCP. En `DonacionService` y `SolicitudService.crear`, las llamadas HTTP ya están fuera de transacción.
- **`@Version` y `OptimisticLockException`:** Presente en `Animal`, `Solicitud` y `Donacion` (ausente en `Perfil`). `GlobalExceptionHandler.java:91` captura `OptimisticLockingFailureException` -> `409`, pero **sin test** que lo ejercite ni prueba de aprobación concurrente.
- **Entidades JPA serializadas en API:** **Ninguna** (100% DTOs `record`).
- **Validaciones Bean Validation faltantes:** Buenas en DTOs principales y paginación; faltan validaciones positivas en `Duration` de properties (`IntegracionesProperties`, `DonacionesProperties`) y en `PathVariable` si se desea rechazar `id <= 0` como `400`.
- **IDOR en cada endpoint con pertenencia:** Implementado en código (`SolicitudService.buscarVisible`/`exigirDuenio`, `DonacionService.obtener`/`listar`, `PerfilController` `/me/perfil`), pero **sin test negativo dedicado** para `GET /api/solicitudes/{id}/historial` y `POST /api/solicitudes/{id}/devolucion`.
- **Audiencia, emisor y clock skew JWT:** Emisor (`iss`) y firma verificados; **no se valida `aud`/`azp`**; clock skew en default de Spring Security (60s), sin documentar ni ajustar.
- **PII en logs:** **Limpio** en las 45 sentencias `log.*` revisadas (no se loguean domicilios, teléfonos, emails ni tokens).
- **Exposición de Actuator y Swagger:** Swagger `false` por defecto en `docker-compose.yml` (`true` en override); `/actuator/prometheus` solo interno con `ROLE_SERVICIO`; **faltan** grupos `liveness`/`readiness` y `show-details: never` explícito.
- **Timeouts y reintentos en clientes HTTP:** Timeouts connect/read explícitos + Circuit Breaker en los 3 clientes; **cero reintentos** hoy (ni siquiera en GET idempotentes de Georef / consulta de pagos; correcto que no haya retry en `POST /checkout/preferences`).
- **Ejecución duplicada de `@Scheduled` con réplicas:** **Riesgo activo** en `TareaVencimientos` (sin `ShedLock`).
- **`spring.threads.virtual.enabled` y `synchronized` con I/O:** Cero `synchronized` en todo el código; `spring.threads.virtual.enabled` **no está habilitado**.
- **Tamaño del pool HikariCP:** **No configurado** (usa default 10 en los 3 servicios).
- **`spring.jpa.open-in-view`:** `false` en los 3 servicios (**OK**).
- **`@Value` dispersa vs `@ConfigurationProperties` validadas:** Cero `@Value` en todo el repo (**OK**).
- **Dependencias vulnerables o desactualizadas:** Versiones base modernas (`Spring Boot 3.5.16`, `Spring Cloud 2025.0.3`, `springdoc 2.8.17`), pero sin escaneo automatizado (Dependabot/Trivy/OWASP/dependency-review) ni `maven-enforcer`.

---

## e) Plan priorizado mapeado a los workstreams de Fase 2

*(Se omiten o reducen los ítems ya mergeados en PRs #1–#12, concentrando el esfuerzo en lo que realmente lleva el repo de **69/100 a 92+/100**.)*

1. **WS-0 — Verdad ejecutable y demo offline** *(cierra H-02)*
   - Verificar en vivo `docker compose up --build -d`, `docker compose ps`, sustitución real de `${...}` en `realm-adopciones.json` de Keycloak 26 y `./scripts/prueba-humo.sh` (Docker 29.9.3 disponible localmente).
   - Agregar `docker-compose.demo.yml` + stubs versionados en `infra/wiremock/` simulando Georef y Mercado Pago para que cualquier revisor ejecute el flujo completo (incluido P5) sin credenciales ni Internet.
2. **WS-1 — Datos (cierre de brechas)** *(cierra H-02, H-06, H-15, H-17, H-19)*
   - Agregar test de concurrencia real (dos aprobaciones simultáneas -> 1 ganador `200` y 1 `409` verificando `@Version` y `GlobalExceptionHandler.handleConcurrencia`).
   - Configurar `hibernate.default_batch_fetch_size: 50`, `@BatchSize(size = 50)` en `Solicitud.eventos`, tamaño explícito de pool HikariCP y verificar `FlywayMigrationIT` contra PostgreSQL 17.
   - Completar (o ejecutar si hay Docker activo) `docs/rendimiento.md` con evidencia real de `EXPLAIN (ANALYZE, BUFFERS)`.
3. **WS-2 — API e idempotencia** *(cierra H-01, H-13)*
   - Implementar `Idempotency-Key` opcional en `POST /api/donaciones` (migración Flyway `V2`, hash del cuerpo, misma clave+cuerpo -> misma respuesta sin doble cobro, misma clave+cuerpo distinto -> `409`/`422` justificado en ADR).
   - Completar anotaciones OpenAPI (`@ApiResponse` 400/401/403/404/409/503 + ejemplos) y exportar `docs/openapi/*.json` desde el build.
   - Redactar ADR de política de versionado (`/api`, cambios aditivos vs breaking).
4. **WS-3 — Concurrencia y resiliencia (cierre de brechas)** *(cierra H-05, H-10, H-15, H-16, H-19)*
   - Activar `spring.threads.virtual.enabled: true` en los servicios MVC.
   - Agregar `Retry` (Resilience4j) únicamente en operaciones GET idempotentes (`GeorefClient`, `MercadoPagoClient.buscarPagoAprobado`).
   - Hacer idempotentes las transiciones internas de `ms-animales` (con identificador de solicitud/operación o `Idempotency-Key` aditivo) para habilitar reintentos y reconciliación seguros.
   - Incorporar `ShedLock` JDBC (o ADR fundado) + métricas de procesadas/fallidas en `TareaVencimientos`; inyectar `Clock` en `DatosDemo` y validar `Duration` positivas.
5. **WS-4 — Funcionalidades faltantes y hardening de Gateway** *(cierra H-11, H-14)*
   - **F1:** tarea programada para controles finales vencidos (R-11) registrando evento en historial + métrica + filtro `?controlVencido=true` para `COORDINADOR`.
   - **F2:** tarea programada de conciliación de donaciones `PENDIENTE` antiguas; webhook de Mercado Pago **solo con aprobación explícita**.
   - **F3:** reconciliador del ADR-06 (tarea + endpoint para `COORDINADOR`) aprovechando transiciones idempotentes; actualizar ADR-06 y README.
   - **F6:** hardening del Gateway (CORS configurable por entorno, cabeceras de seguridad, límite de tamaño de body).
   - *(Opcionales F4 reportes y F5 eventos de dominio con `ApplicationEventPublisher` según preferencia.)*
6. **WS-5 — Seguridad** *(cierra H-04, H-07, H-09, H-12)*
   - Completar tests negativos IDOR (`historial`, `devolucion`) y matriz completa de roles en los 3 servicios.
   - Habilitar probes `liveness`/`readiness` y `show-details: never` en Actuator.
   - Revisar/documentar validación de audiencia (`aud`/`azp`) y clock skew; crear `docs/SEGURIDAD.md` y `SECURITY.md`.
   - Agregar Dependabot, CodeQL, Trivy y gitleaks.
7. **WS-6 — Calidad automatizada** *(cierra H-08)*
   - Agregar Maven Wrapper (`mvnw`), `maven-enforcer-plugin` (Java 21, Maven 3.9, convergencia), Checkstyle o Spotless, SpotBugs, ArchUnit y umbral `jacoco:check` (≥80% en dominio y servicios) + reporte agregado.
8. **WS-7 — Tests y cobertura de `tpi-common`** *(cierra H-06, H-19)*
   - Elevar cobertura de `tpi-common` (hoy 57.2%) cubriendo todos los handlers de `GlobalExceptionHandler`, `JsonAuthenticationEntryPoint`, `JsonAccessDeniedHandler` y `CorrelationIdInterceptor`.
   - Etiquetar `@Tag("unit")` / `@Tag("integration")` / `@Tag("e2e")` y estabilizar márgenes de tiempo en tests de resiliencia.
   - Sumar ejecución E2E (`prueba-humo.sh` o Newman) contra `docker-compose.demo.yml` en CI.
9. **WS-8 — Observabilidad y operación** *(cierra H-04, H-10, H-18)*
   - Configurar `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s` en los 4 servicios.
   - Sumar métricas de negocio en `ms-donaciones` y `TareaVencimientos`.
   - Redactar `docs/OPERACION.md` (runbook: arranque, healthchecks, troubleshooting, backup de volúmenes, rotación de secretos).
10. **WS-9 — Docker, CI/CD y cadena de suministro** *(cierra H-02, H-07, H-20)*
    - Labels OCI y versiones fijadas en Dockerfiles; límites de recursos en `docker-compose.yml`.
    - Ampliar `.github/workflows/ci.yml`: `concurrency`, jobs `verify`, `integration-tests` (Testcontainers), `e2e-demo` (Compose + WireMock), seguridad (`CodeQL`, `Trivy`, `gitleaks`, `dependency-review`) y publicación en GHCR + release en tags.
11. **WS-10 — Documentación y presentación** *(cierra H-03, H-18)*
    - Corregir contradicciones (`docs/PATRONES.md:46`, `docs/ADR.md:67`, `README.md:95`), archivar/eliminar `docs/AUDITORIA.md`, limpiar referencias académicas remanentes.
    - Actualizar `README.md` (resumen en inglés de 8–10 líneas, quickstart de 3 comandos con demo WireMock, tabla con cobertura y tiempos reales medidos, tabla de ADRs al día).
    - Agregar `CONTRIBUTING.md`, `CHANGELOG.md`, plantillas de issue/PR, `docs/ARQUITECTURA.md` e índice de ADRs con estado y fecha; proponer descripción, topics y tag `v1.0.0`.
12. **WS-11 (Opcional, requiere aprobación)** — Renombrado `tpi-common` → `common` y eliminación del prefijo `tpi`.

---

## Preguntas abiertas antes de iniciar Fase 2

1. **Docker disponible:** detecté `Docker 29.9.3` instalado localmente; ¿confirmás que el *daemon* está levantado y que puedo ejecutar `docker compose up`, `FlywayMigrationIT` (Testcontainers) y el benchmark de `EXPLAIN ANALYZE` directamente desde acá en WS-0 / WS-1?
2. **Material de defensa / archivos locales:** `docs/DEFENSA.md`, `PREGUNTAS.md`, `GUIAS-ESTUDIO.md`, `GITLAB.md` y el `.docx` ya no están en `HEAD` (solo en el commit inicial `7b4c66b`), pero todavía existen `docs/AUDITORIA.md` (con tono académico) y los archivos locales ignorados `README-CLASES.md` y `RESUMEN-EXPOSICION.md` (listados en `.gitignore`). ¿Aprobás eliminar `docs/AUDITORIA.md` (o moverlo a archivo histórico) y quitar esas dos entradas de `.gitignore` (sin tocar tus archivos locales en disco)?
3. **Webhook de Mercado Pago (WS-4 / F2):** ¿querés implementar solo la tarea programada de conciliación de donaciones `PENDIENTE` antiguas, o también exponemos el endpoint público `POST /api/donaciones/webhooks/mercadopago` con validación de firma `x-signature` y ruta pública en el Gateway?
4. **Opcionales de WS-4 (F4 y F5) y WS-11 (renombrado):**
   - ¿Incluimos **F4** (reporte de conteo por estado y tiempo promedio de resolución) y **F5** (eventos de dominio con `ApplicationEventPublisher` y listener de log estructurado)?
   - ¿Aprobás ejecutar **WS-11** (renombrar `tpi-common` → `common`, paquetes, volúmenes, contenedores y archivos `TPI-*`), o preferís conservar el namespace actual para no cambiar nombres ya consolidados?
5. **Política de `Idempotency-Key` con cuerpo distinto (WS-2):** cuando llega la misma `Idempotency-Key` en `POST /api/donaciones` con un monto distinto, ¿preferís `409 Conflict` (alineado al resto de los conflictos del proyecto) o `422 Unprocessable Entity` (estándar IETF `draft-ietf-httpapi-idempotency-key-header`)?
