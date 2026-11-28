# Registro de Decisiones Arquitectónicas (ADR)

Formato: contexto → alternativas → decisión → consecuencias → evidencia verificable en el código.

## Índice de ADRs

| ID | Decisión | Estado | Fecha |
|---|---|---|---|
| [ADR-01](#adr-01-tres-servicios-separados-por-dueño-de-la-información) | Tres microservicios separados por dueño de la información (`ms-animales`, `ms-adopciones`, `ms-donaciones`) | Aceptado | 2026-10 |
| [ADR-02](#adr-02-comunicación-síncrona-rest-con-api-interna) | Comunicación síncrona REST hacia API interna `/internal/animales/**` | Aceptado | 2026-10 |
| [ADR-03](#adr-03-animales-decide-la-validez-de-cada-transición) | `ms-animales` valida y gobierna cada transición de estado del animal | Aceptado | 2026-10 |
| [ADR-04](#adr-04-seguridad-en-dos-capas-y-token-de-servicio-trusted-zone) | Seguridad en dos capas (Gateway + servicios) y token `client_credentials` (`ROLE_SERVICIO`) | Aceptado | 2026-10 |
| [ADR-05](#adr-05-emisor-del-token-fijo-con-kc_hostname) | Emisor JWT público fijo (`KC_HOSTNAME`) con descarga interna de JWKS | Aceptado | 2026-10 |
| [ADR-06](#adr-06-consistencia-entre-servicios-sin-transacción-distribuida) | Consistencia entre servicios con `flush` local previo, *rollback* ante fallo remoto y reconciliación | Aceptado (ampliado por ADR-21) | 2026-10 |
| [ADR-07](#adr-07-concurrencia-columnas-de-bloqueo-con-unique--bloqueo-optimista) | Control de concurrencia con columnas `UNIQUE` condicionales y bloqueo optimista `@Version` | Aceptado | 2026-10 |
| [ADR-08](#adr-08-persistencia-una-instancia-esquema-y-usuario-por-servicio-keycloak-aparte) | Persistencia aislada por esquema y usuario PostgreSQL; base independiente para Keycloak | Aceptado (evolucionado por ADR-26) | 2026-10 |
| [ADR-09](#adr-09-componente-común-qué-se-comparte-y-qué-no) | Librería común liviana (`tpi-common`) sin acoplar modelos ni al Gateway reactivo | Aceptado | 2026-10 |
| [ADR-10](#adr-10-georef-como-fuente-de-ubicación-y-distancia-en-línea-recta) | Geolocalización con API Georef + distancia Haversine con degradación no bloqueante | Aceptado | 2026-10 |
| [ADR-11](#adr-11-mercado-pago-checkout-pro-con-webhook-firmado-reconciliación-periódica-y-verificación-bajo-demanda) | Integración Mercado Pago Checkout Pro con webhook HMAC-SHA256, reconciliación y consulta | Aceptado (ampliado por ADR-21) | 2026-11 |
| [ADR-12](#adr-12-sin-lombok-records-para-dto) | Prohibición de Lombok; `record` de Java 21 para DTOs y propiedades tipadas | Aceptado | 2026-10 |
| [ADR-13](#adr-13-estrategia-de-pruebas-en-tres-niveles-unidadslice-testcontainers-y-e2e) | Estrategia de pruebas en tres niveles: unidad/slice, Testcontainers PostgreSQL 17 y E2E Compose | Aceptado | 2026-11 |
| [ADR-14](#adr-14-docker-construcción-multi-stage-con-caché-buildkit-y-usuario-sin-privilegios) | Construcción Docker multi-stage con caché BuildKit, usuario no root y healthchecks nativos | Aceptado (ampliado por ADR-25) | 2026-11 |
| [ADR-15](#adr-15-renovación-de-token-de-servicio-con-reentrantlock-y-precalentamiento-fuera-de-la-transacción) | Caché `volatile` + `ReentrantLock` en `ServiceTokenProvider` y precalentamiento fuera de transacción | Aceptado | 2026-11 |
| [ADR-16](#adr-16-timeouts-diferenciados-y-circuit-breaker-resilience4j-en-clientes-http) | Timeouts diferenciados y Circuit Breaker programático (Resilience4j) en clientes HTTP | Aceptado | 2026-11 |
| [ADR-17](#adr-17-resolución-de-la-inconsistencia-residual-del-adr-06-estado-actual-vs-outbox-vs-saga-con-compensación) | Evaluación comparativa: estado actual + reconciliación vs. Outbox vs. Saga orquestada ligera | Propuesta (referencia evolutiva) | 2026-11 |
| [ADR-18](#adr-18-idempotencia-opcional-en-post-apidonaciones-con-idempotency-key-y-conflicto-409) | Idempotencia opcional en `POST /api/donaciones` con `Idempotency-Key` y hash SHA-256 (`409`) | Aceptado | 2026-11 |
| [ADR-19](#adr-19-política-de-versionado-de-la-api-rest-api) | Política de evolución aditiva bajo `/api/**` y reserva de `/api/v2/**` para cambios incompatibles | Aceptado | 2026-11 |
| [ADR-20](#adr-20-virtual-threads-reintentos-selectivos-en-get-idempotentes-transiciones-idempotentes-en-animales-y-shedlock-jdbc) | Virtual threads, `Retry` solo en GET, transiciones idempotentes con `solicitudId` y ShedLock JDBC | Aceptado | 2026-11 |
| [ADR-21](#adr-21-cierre-de-brechas-operativas-y-hardening-del-gateway-ws-4) | Alertas de controles vencidos, webhook MP, reconciliador ADR-06, reportes, eventos y hardening Gateway | Aceptado | 2026-11 |
| [ADR-22](#adr-22-endurecimiento-de-jwt-azpaudclock-skew-probes-actuator-y-escaneo-continuo-ws-5) | Validación estricta de JWT (`azp`/`aud`/*clock skew*), PKCE, probes Actuator y seguridad continua | Aceptado | 2026-11 |
| [ADR-23](#adr-23-compuertas-automatizadas-de-calidad-arquitectura-y-cobertura-ws-6) | Maven Wrapper, Enforcer, Checkstyle, SpotBugs, ArchUnit y umbral bloqueante de JaCoCo | Aceptado | 2026-11 |
| [ADR-24](#adr-24--apagado-ordenado-graceful-shutdown-y-métricas-de-negocio-en-prometheus-ws-8) | Graceful shutdown (`20s`/`30s`), contadores/gauges de negocio en Prometheus y runbook operativo | Aceptado | 2026-11 |
| [ADR-25](#adr-25--fijación-de-imágenes-base-metadatos-oci-límites-de-recursos-y-pipeline-cicd-completo-ws-9) | Fijación de imágenes base, etiquetas OCI, límites de recursos y pipeline CI/CD completo con GHCR | Aceptado | 2026-11 |
| [ADR-26](#adr-26--migraciones-flyway-paginación-aditiva-y-protección-interna-de-prometheus) | Migraciones Flyway + `ddl-auto: validate`, paginación aditiva (`X-Total-Count`) y `/actuator/prometheus` interno | Aceptado | 2026-11 |

---

## ADR-01. Tres servicios separados por dueño de la información

- **Contexto:** separar responsabilidades del dominio según propiedad de datos, invariantes y acoplamiento con proveedores externos.
- **Alternativas:** (a) un monolito modular; (b) Animales + Adopciones (Donaciones dentro de Adopciones); (c) tres servicios; (d) un servicio por entidad (perfil, solicitud…).
- **Decisión:** (c). Animales es dueño del estado del animal; Adopciones, del proceso; Donaciones, del ciclo de pago.
- **Justificación:** dueños de datos distintos (el animal existe sin solicitudes), ritmos de cambio distintos, reglas distintas (Animales protege la coherencia del estado; Adopciones, la del proceso). Donaciones tiene datos, reglas y proveedor propios: cambiar de plataforma de pagos no toca a los otros. (d) multiplicaría llamadas de red sin aislar invariantes reales.
- **Consecuencias:** Donaciones opera de forma autónoma frente a Mercado Pago, mientras que la coordinación entre dominios se concentra entre `ms-adopciones` y `ms-animales`.
- **Evidencia:** `ms-animales/.../model/Animal.java`, `EstadoAnimal.java`; `ms-adopciones/.../service/SolicitudService.java`; `ms-donaciones/.../service/DonacionService.java`.

## ADR-02. Comunicación síncrona REST con API interna

- **Contexto:** al presentar y al decidir, Adopciones necesita el estado actual del animal y que Animales confirme la transición antes de responder al usuario.
- **Alternativas:** mensajería asíncrona (RabbitMQ/Kafka), réplica local del catálogo, REST síncrono.
- **Decisión:** REST síncrono con `RestClient` (Spring MVC), timeouts de 3 s, a una API interna `/internal/animales/**` que el Gateway no enruta.
- **Consecuencias:** si Animales no responde, presentar y aprobar fallan con 503 (R-01 es bloqueante: “ante la duda no se avanza”). No se agrega infraestructura de mensajería. Acoplamiento temporal asumido.
- **Evidencia:** `AnimalesClient`, `ClientesConfig`, `AnimalInternoController`, `gateway/application.yml` (sin ruta interna), prueba `siAnimalesNoRespondeAlAprobarLaSolicitudSiguePendiente`.

## ADR-03. Animales decide la validez de cada transición

- **Contexto:** dos servicios podrían querer cambiar el estado del animal (Coordinador por la API pública y el proceso por la interna).
- **Decisión:** el estado solo cambia por métodos de la entidad `Animal`; las transiciones del proceso están tabuladas en `EstadoAnimal`. Adopciones *pide*; Animales *decide* (409 si es inválida). `@Version` evita que dos operaciones concurrentes se pisen.
- **Evidencia:** `EstadoAnimal.admiteTransicionDelProceso`, `Animal.cambiarEstadoPorProceso`, `AnimalTest` (5 válidas, 7 inválidas, R-13).

## ADR-04. Seguridad en dos capas y token de servicio (trusted zone)

- **Contexto:** diseño de seguridad y delimitación de la *trusted zone*.
- **Alternativas:** validar solo en el Gateway; reenviar el token del usuario a Animales; mTLS entre servicios.
- **Decisión:** el Gateway valida el JWT (autenticación en el borde y rechazo de tokens de servicio en `/api/**`); **cada servicio vuelve a validarlo** y aplica la autorización fina por rol y por pertenencia. Adopciones obtiene su propio token (client credentials, rol SERVICIO) para la API interna. Los servicios no publican puertos en modo producción.
- **Justificación:** si alguien alcanzara un servicio sin pasar por el Gateway, igual necesita un token válido y el rol correcto (verificado: llamada directa sin token → 401; usuario a `/internal` → 403). Reenviar el token del usuario haría que un COORDINADOR pudiera ordenar transiciones del proceso directamente.
- **Consecuencias:** doble verificación de firma (costo bajo: claves en caché). mTLS descartado por complejidad.
- **Evidencia:** `gateway/.../SecurityConfig.java`, `*/config/SecurityConfig.java`, `ServiceTokenProvider`, `realm-adopciones.json`.

## ADR-05. Emisor del token fijo con KC_HOSTNAME

- **Contexto:** el `iss` depende de la URL usada para pedir el token (CONTRATOS §3.4).
- **Decisión:** `KC_HOSTNAME=http://localhost:8180`; servicios con `issuer-uri` público + `jwk-set-uri` interno.
- **Alternativas:** validar sin emisor (inseguro); poner `keycloak` en el archivo hosts de cada máquina (paso manual oculto).
- **Evidencia:** `docker-compose.yml` (`x-keycloak-validacion`), `application.yml` de cada módulo, verificación local: token con `iss=http://keycloak:8080/...` → 401.

## ADR-06. Consistencia entre servicios sin transacción distribuida

- **Contexto:** aprobar cambia dos bases (solicitud y animal).
- **Alternativas:** 2PC/XA (no disponible en REST), saga con eventos y compensaciones, orden local-primero con *rollback*.
- **Decisión:** dentro de una transacción local se aplica el cambio de la solicitud y se hace `flush` (aparecen conflictos de versión o UNIQUE **antes** de tocar Animales); luego se llama a Animales; si falla, la excepción revierte el cambio local. Las llamadas a Georef y la consulta inicial a Animales se hacen fuera de la transacción.
- **Consecuencias:** nunca queda una solicitud APROBADA con el animal sin reservar. Caso residual: Animales confirma y el *commit* local falla (por ejemplo, caída de la base en ese instante); quedaría el animal RESERVADO sin solicitud APROBADA. En **WS-4 (ADR-21)** se añadió `ReconciliadorAdopciones` (tarea programada protegida con ShedLock + endpoint `POST /api/solicitudes/reconciliacion` para `COORDINADOR`), que detecta y repara de forma idempotente estas desincronizaciones y reporta conflictos ambiguos para revisión manual.
- **Evidencia:** `SolicitudService.conCambioDeAnimal`, `ReconciliadorAdopciones`, `TareaReconciliacionAdopciones`, pruebas `r07SiAnimalesRechazaLaReservaNoSeAprueba`, `siAnimalesNoRespondeAlAprobar...` y `AdopcionesFuncionalidadesTest`.

## ADR-07. Concurrencia: columnas de bloqueo con UNIQUE + bloqueo optimista

- **Contexto:** R-01 y R-02 ante dos peticiones simultáneas (riesgo señalado en el dominio §13).
- **Alternativas:** índice parcial de PostgreSQL (`WHERE estado='PENDIENTE'`), bloqueo pesimista, verificación solo en memoria.
- **Decisión:** columnas `animal_pendiente` y `adoptante_activo` que la entidad completa según el estado (null si no aplica) con restricción UNIQUE; más `@Version` en `Solicitud`, `Animal` y `Donacion`.
- **Justificación:** funciona con `ddl-auto` (contrato) y con H2 en las pruebas; PostgreSQL admite múltiples NULL en UNIQUE.
- **Evidencia:** `Solicitud` (Javadoc y `cambiarEstado`), `SolicitudRepositoryTest`.

## ADR-08. Persistencia: una instancia, esquema y usuario por servicio; Keycloak aparte

- **Alternativas:** una base por servicio (más contenedores), esquemas compartidos con un solo usuario (aislamiento débil).
- **Decisión:** base `tpi` con esquemas `animales`, `adopciones`, `donaciones`, cada uno propiedad de su usuario; Keycloak en `keycloak-db`.
- **Consecuencias:** aislamiento real por permisos SQL con un solo contenedor de negocio; Keycloak se recrea sin tocar datos de negocio. La evolución de cada esquema se gestiona mediante migraciones SQL versionadas con **Flyway** y `ddl-auto: validate` (ver [ADR-26](#adr-26--migraciones-flyway-paginación-aditiva-y-protección-interna-de-prometheus)).
- **Evidencia:** `infra/postgres/init/01-esquemas.sh`, `hibernate.default_schema`, `*/src/main/resources/db/migration/V*.sql`.

## ADR-09. Componente común: qué se comparte y qué no

- **Se comparte** (igual en los tres servicios): `ErrorResponse`, excepciones, `GlobalExceptionHandler`, 401/403 en JSON, `KeycloakRoleConverter`, `ResourceServerDefaults`, `Roles`, correlación.
- **No se comparte:** entidades, DTO, reglas y rutas de seguridad de cada servicio; los estados del animal que usa Adopciones son una copia local (`adopciones.client.EstadoAnimal`): son contrato HTTP, no modelo compartido.
- **Distribución:** `mvn install` al `.m2`; dependencias Spring en `provided`. Los Dockerfiles repiten ese mecanismo dentro de la imagen.
- **Gateway:** no depende del JAR (reactivo); repite 15 líneas del conversor de roles a propósito.

## ADR-10. Georef como fuente de ubicación y distancia en línea recta

- **Alternativas:** OSRM (requiere desplegar y procesar el mapa; dudoso como “API pública por Internet”), Google Distance Matrix (clave y costos).
- **Decisión:** Georef `/direcciones` + Haversine propio; falla externa → `distanciaNoVerificada` (no bloquea: la decisión es humana).
- **Evidencia:** `GeorefClient` (formato verificado contra la API real), `CalculadoraDistancia`, `EvaluadorSolicitud`.

## ADR-11. Mercado Pago: Checkout Pro con webhook firmado, reconciliación periódica y verificación bajo demanda

- **Decisión:** Checkout Pro (preferencia + `init_point`) con tres vías complementarias e idempotentes sobre `uk_donacion_pago_id`:
  1. **Webhook público firmado** (`POST /api/donaciones/webhooks/mercadopago`, incorporado en WS-4 / ADR-21): valida la cabecera `x-signature` (HMAC-SHA256 con `MP_WEBHOOK_SECRET`) y consulta el detalle autoritativo `GET /v1/payments/{id}` antes de confirmar.
  2. **Reconciliación programada** (`TareaReconciliacionDonaciones`, protegida con ShedLock sobre `donaciones.shedlock`): revisa donaciones `PENDIENTE` antiguas consultando `/v1/payments/search?external_reference=...`.
  3. **Verificación bajo demanda** (`GET /api/donaciones/{id}`): mantiene compatibilidad cuando se ejecuta en local sin túnel HTTPS.
- **Consecuencias:** en entornos sin túnel HTTPS público las donaciones pendientes siguen confirmándose automáticamente vía reconciliación o al consultarlas; con túnel/HTTPS público el webhook confirma el pago en tiempo real sin confiar en el cuerpo entrante.
- **Evidencia:** `MercadoPagoClient`, `DonacionService`, `VerificadorFirmaMercadoPago`, `MercadoPagoWebhookController`, `TareaReconciliacionDonaciones`.

## ADR-12. Sin Lombok; records para DTO

- **Contexto:** Lombok introduce un procesador de anotaciones acoplado a la versión del compilador (distintos entornos JDK).
- **Decisión:** records para DTO y configuración; entidades con getters explícitos y métodos de dominio en lugar de setters.
- **Consecuencia:** algo más de código, pero las entidades no exponen setters que permitan saltear las reglas.

## ADR-13. Estrategia de pruebas en tres niveles: unidad/slice, Testcontainers y E2E

- **Decisión:**
  1. **Unidad y slice rápido (`@Tag("unit")` / `@Tag("integration")` en memoria):** JUnit 5, Mockito, ArchUnit (`@ArchTag("unit")`), H2 en modo PostgreSQL, `MockRestServiceServer`, servidores HTTP locales para resiliencia, `Clock` inyectable y `jwt()`/`mockJwt()`. Corre con `./mvnw verify` sin requerir Docker ni acceso a Internet.
  2. **Integración real de persistencia (`-Pintegration-tests`, `*IT`):** Testcontainers levanta PostgreSQL 17 real, ejecuta todas las migraciones Flyway (`V1..V3`) y valida el mapeo JPA con `ddl-auto: validate`.
  3. **End-to-End del sistema completo (`docker-compose.demo.yml` + `scripts/prueba-humo.sh`):** valida los 8 contenedores reales (Gateway, 3 microservicios, Keycloak, 2 PostgreSQL y WireMock) recorriendo P1–P5, RBAC, IDOR, `Idempotency-Key` y propagación de `X-Correlation-ID`.
- **Consecuencia:** feedback rápido en desarrollo local (`-Dgroups=unit` en ~1 min) sin renunciar a validación real de dialecto SQL, migraciones, contenedores y OIDC en CI.

## ADR-14. Docker: construcción multi-stage con caché BuildKit y usuario sin privilegios

- **Decisión:** cada Dockerfile construye desde la raíz del reactor cacheando dependencias con `--mount=type=cache,target=/root/.m2`, ejecuta el runtime sobre JRE 21 mínimo con usuario sin privilegios (`USER app`), `SWAGGER_ENABLED=false` por defecto, healthchecks nativos con bash `/dev/tcp`, `depends_on` con `service_healthy` y rotación de logs JSON (ampliado en [ADR-25](#adr-25--fijación-de-imágenes-base-metadatos-oci-límites-de-recursos-y-pipeline-cicd-completo-ws-9)).

## ADR-15. Renovación de token de servicio con `ReentrantLock` y precalentamiento fuera de la transacción

- **Contexto:** `ServiceTokenProvider.obtenerToken()` utilizaba `synchronized` envolviendo la llamada HTTP `POST` a Keycloak, y `AnimalesClient.cambiarEstado(...)` pedía el token de forma perezosa desde adentro de `tx.execute(...)` en `SolicitudService.conCambioDeAnimal` y `vencerEntrega`.
- **Alternativas:** (a) mantener `synchronized`; (b) sacar `animalesClient.cambiarEstado(...)` fuera de la transacción de base de datos; (c) caché `volatile` (`TokenCacheado`) con camino rápido sin lock, `ReentrantLock` con doble verificación solo al renovar, y precalentamiento del token antes de abrir `tx.execute(...)`.
- **Decisión:** (c).
- **Justificación:**
  - **`ReentrantLock` vs `synchronized`:** en Java 21, el I/O bloqueante dentro de un bloque `synchronized` fija (*pinning*) el hilo portador si se emplean *virtual threads*. Además, leer un `record` inmutable desde un campo `volatile` elimina la contención en el camino feliz, mientras que la doble verificación dentro del `ReentrantLock` garantiza una única llamada a Keycloak cuando concurren múltiples hilos con el token vencido.
  - **Por qué no se movió `cambiarEstado` fuera de la transacción:** sacarlo alteraría la semántica de fallos documentada en [ADR-06](#adr-06-consistencia-entre-servicios-sin-transacción-distribuida) (si `ms-animales` rechaza con 409 o no responde con 503, el *rollback* automático evita dejar una solicitud `APROBADA` sin el animal `RESERVADO`). Precalentar con `animalesClient.asegurarToken()` antes de `tx.execute(...)` saca el eventual `POST` a Keycloak fuera de la transacción sin cambiar las garantías de consistencia.
- **Evidencia:** `ServiceTokenProvider`, `AnimalesClient.asegurarToken`, `SolicitudService` (`conCambioDeAnimal`, `vencerEntrega`), `ServiceTokenProviderTest`.

## ADR-16. Timeouts diferenciados y Circuit Breaker (Resilience4j) en clientes HTTP

- **Contexto:** los clientes HTTP (`GeorefClient`, `AnimalesClient`, `ServiceTokenProvider`, `MercadoPagoClient`) compartían un único `timeout` para conexión y lectura, y ante una caída sostenida de Georef, `ms-animales` o Mercado Pago cada solicitud volvía a agotar el tiempo completo de espera antes de degradar.
- **Alternativas:** (a) solo timeouts sin circuit breaker; (b) `@CircuitBreaker` de `resilience4j-spring-boot3` con proxies AOP; (c) timeouts explícitos de conexión y lectura parametrizables en `application.yml` + `CircuitBreaker` programático (`resilience4j-circuitbreaker`, gestionado por el BOM de Spring Cloud) inyectado en los clientes con comportamiento degradado definido.
- **Decisión:** (c).
- **Justificación:**
  - **Dónde se aplica:** únicamente en `georef`, `animales` y `mercadoPago`, que ya tienen degradación explícita en el contrato (`distanciaNoVerificada = true` en R-06; `503` con *rollback* en ADR-06; `503` al crear donación y `PENDIENTE` con `verificado = false` al consultar en D-02). No se aplica a `ServiceTokenProvider` (Keycloak), que ya cachea tokens y precalienta fuera de transacción (ADR-15).
  - **Sin romper contratos ni confundir errores de negocio:** el circuito registra exclusivamente `ServicioNoDisponibleException` (`recordExceptions`). En `AnimalesClient`, respuestas `404` (`RecursoNoEncontradoException`) y `409` (`ConflictoException`) son rechazos válidos del dominio y nunca abren el circuito. Cuando el circuito está abierto, `CallNotPermittedException` se traduce a `ServicioNoDisponibleException`, preservando intactos los contratos HTTP existentes.
  - **Enfoque programático vs AOP:** evita proxies de AspectJ, permite testear timeout real, apertura y recuperación directamente contra un `HttpServer` local sin levantar el contexto Spring completo, y expone la configuración completa (`failure-rate-threshold`, `sliding-window-size`, `minimum-number-of-calls`, `wait-duration-in-open-state`, `permitted-number-of-calls-in-half-open-state`) en `application.yml`.
- **Evidencia:** `IntegracionesProperties`, `DonacionesProperties`, `ClientesConfig` (ambos servicios), `GeorefClient`, `AnimalesClient`, `MercadoPagoClient`, `ClientesResilienciaTest`, `MercadoPagoResilienciaTest`.

## ADR-17. Resolución de la inconsistencia residual del ADR-06: estado actual vs. Outbox vs. Saga con compensación

- **Estado:** **propuesta** (análisis y diseño de evolución sobre [ADR-06](#adr-06-consistencia-entre-servicios-sin-transacción-distribuida); **no implementada** en el código actual).
- **Contexto:** todas las transiciones coordinadas en `SolicitudService` (`conCambioDeAnimal` —usado por `aprobar`, `confirmarEntrega`, `cancelarEntrega`, `registrarControl` y `devolver`— y `vencerEntrega`) siguen el orden de ADR-06: precalientan el token fuera de la transacción (ADR-15), abren `tx.execute(...)`, aplican el cambio de `Solicitud` con `saveAndFlush(...)` e invocan `PUT /internal/animales/{id}/estado` antes del `COMMIT`. Si `ms-animales` rechaza (`409`) o falla (`503`), la transacción local hace *rollback*. Sin embargo, persiste una ventana de inconsistencia cuando `ms-animales` **sí** confirma el cambio en el esquema `animales` y luego ocurre cualquiera de estos dos fallos:
  1. **Fallo del `COMMIT` local en `adopciones`** (caída de PostgreSQL, corte JDBC o caída del proceso `ms-adopciones` entre el `200 OK` de `ms-animales` y el `COMMIT`).
  2. **Timeout de lectura o corte TCP tras el `COMMIT` remoto:** `ms-animales` aplica y confirma la transición, pero la respuesta excede `read-timeout` (ADR-16); `AnimalesClient` lanza `ServicioNoDisponibleException`, `ms-adopciones` revierte la transacción local y devuelve `503`. Como `Animal.cambiarEstadoPorProceso` rechaza `X -> X` con `409` (no es idempotente), el reintento manual tampoco destraba la operación.

  El impacto concreto depende de la transición afectada:
  - En `aprobar` (`DISPONIBLE -> RESERVADO`), el animal queda `RESERVADO` sin ninguna solicitud `APROBADA`: no lo captura `TareaVencimientos` (que consulta solicitudes `APROBADA`) y tampoco puede marcarse `NO_DISPONIBLE` por **R-13** (`EstadoAnimal.tieneAdopcionEnCurso()`).
  - En `confirmarEntrega` (`RESERVADO -> EN_ADAPTACION`), la solicitud queda `APROBADA` y a los 7 días `TareaVencimientos` podría enviar `DISPONIBLE` y liberar en el catálogo un animal ya entregado.
  - En `registrarControl(CONFORME)` (`EN_ADAPTACION -> ADOPTADO`), el animal alcanza un estado **terminal** sin transiciones salientes en `EstadoAnimal` mientras la solicitud permanece `EN_ADAPTACION`.

  Adicionalmente, ejecutar la llamada HTTP dentro de `tx.execute(...)` retiene una conexión del pool HikariCP y el bloqueo de fila de `solicitud` mientras dura la espera de red.

- **Alternativas comparadas:**

  | Criterio | (a) Estado actual (ADR-06) | (b) Transactional Outbox + reintentos idempotentes | (c) Saga orquestada con compensación y reconciliación |
  |---|---|---|---|
  | **Mecanismo** | `flush` local + `PUT` síncrono dentro de `tx.execute(...)` + `COMMIT`/`ROLLBACK`. | `Tx` local guarda `Solicitud` + fila en `outbox_cambio_animal` (con `operacion_id`). Un *relay* (`@Scheduled` con `FOR UPDATE SKIP LOCKED`) reenvía `PUT` con `Idempotency-Key` hasta que `ms-animales` confirme. | `Tx 1` corta registra una saga `EN_PROGRESO` (`operacion_id`, bloquea comandos concurrentes). Fuera de `Tx`, llama `PUT` con `Idempotency-Key`. `Tx 2` confirma (`COMPLETADA`) o revierte ante `409`. Si falla `Tx 2` o hay timeout, un reconciliador `@Scheduled` consulta el estado remoto y compensa o avanza. |
  | **Riesgo residual** | Divergencia permanente ante fallo de `COMMIT` local o timeout tras `COMMIT` remoto; requiere corrección manual. | Elimina la divergencia entre `COMMIT` local y envío remoto (*effectively-once* con tabla de idempotencia en `ms-animales`). | Elimina la divergencia tanto ante fallo de `COMMIT` en `Tx 2` como ante timeout de lectura tras `COMMIT` remoto. |
  | **Compatibilidad con R-07 y ADR-03 (*Animales decide*)** | Total: nunca aprueba sin confirmación previa de `ms-animales`. | **Incompatible en `aprobar` sin rediseño:** al confirmar `APROBADA` antes de consultar `ms-animales`, un rechazo concurrente (`409`, p. ej. animal marcado `NO_DISPONIBLE`) deja la solicitud ya aprobada y exige compensación posterior, rompiendo “ante la duda no se avanza”. En cambio, es seguro en transiciones **posteriores** a la reserva gracias a R-13. | Total: `ms-animales` sigue validando `DISPONIBLE -> RESERVADO` antes de pasar la solicitud a `APROBADA` (`409` síncrono intacto). |
  | **Contrato HTTP (`CONTRATOS.md`)** | Síncrono (`200`/`409`/`503`), sin cambios. | Tiende a consistencia eventual (`202 Accepted` o `200` con el catálogo desfasado temporalmente). | Conserva el contrato síncrono (`200`/`409`/`503`) en el camino feliz y en rechazos `409`; solo los casos fallidos se resuelven en segundo plano. |
  | **Conexión JDBC durante HTTP** | Retenida durante el `PUT` (acotada por `read-timeout` y ADR-15/16). | Liberada antes de cualquier I/O remoto. | Liberada antes de cualquier I/O remoto (`Tx 1` y `Tx 2` son transacciones locales breves). |
  | **Complejidad agregada** | Mínima (ya implementada). | Tabla `outbox` en `adopciones`, tabla `operacion_procesada` + `Idempotency-Key` en `animales`, *worker* con *backoff* y manejo de *dead-letter*. | Tabla `saga_cambio_animal` (o estado transitorio) en `adopciones`, tabla `operacion_procesada` + `Idempotency-Key` + endpoint de consulta de operación en `animales`, tarea reconciliadora `@Scheduled` y política explícita por transición (compensación vs. *forward recovery*). |

- **Decisión recomendada para este alcance:**
  1. **Mantener (a) (ADR-06) sin implementar cambios ahora dentro del alcance actual del proyecto:** para el volumen real de un refugio (decenas de adopciones semanales) la probabilidad de fallo exactamente entre el `200 OK` de `ms-animales` y el `COMMIT` local es muy baja, el contrato REST síncrono queda limpio y no se introduce complejidad prematura ni infraestructura de mensajería. Como mitigación operativa inmediata bastan logs estructurados con `X-Correlation-ID` y `animalId`/`solicitudId` (ya existentes) más verificación ante alertas de error de `COMMIT` o `503`.
  2. **Cuando el sistema requiera eliminar formalmente la ventana residual (diseño objetivo recomendado sin broker): adoptar (c) Saga orquestada ligera sobre PostgreSQL con `Idempotency-Key` en `ms-animales` y reconciliación periódica (`@Scheduled`), diferenciando dos políticas según la máquina de estados de `EstadoAnimal`:**
     - **Compensación (*backward recovery*) en `aprobar`:** `DISPONIBLE -> RESERVADO` es la única transición expuesta a rechazo concurrente de negocio (`409` por cambio administrativo a `NO_DISPONIBLE`) y además ya dispone de la transición inversa `RESERVADO -> DISPONIBLE` en `EstadoAnimal.TRANSICIONES_DEL_PROCESO`. Si `Tx 2` falla o hay timeout y el Coordinador recibió `500`/`503`, el reconciliador consulta `GET /internal/operaciones/{operacionId}` en `ms-animales`; si la reserva se aplicó, envía la compensación `PUT /internal/animales/{id}/estado {DISPONIBLE}` (con su propia `Idempotency-Key`) y devuelve la solicitud a `PENDIENTE`, evitando aprobar una operación que el usuario vio fallar.
     - **Avance idempotente (*forward recovery*, equivalente a Outbox) en las transiciones post-reserva:** una vez que el animal está `RESERVADO` o `EN_ADAPTACION`, **R-13** impide que cambios administrativos interfieran en `ms-animales`, y además `EN_ADAPTACION -> RESERVADO` no existe y `ADOPTADO` es terminal (transición pivote no compensable). Ante un fallo de `Tx 2` o timeout en `confirmarEntrega`, `registrarControl`, `devolver`, `cancelarEntrega` o `vencerEntrega`, el reconciliador reenvía la operación con la misma `Idempotency-Key` (si aún no se aplicó) y completa la persistencia local en `adopciones`.
- **Justificación de por qué (c) ligera supera a (b) puro en este dominio:**
  - **Outbox puro (b)** prioriza desacoplamiento asíncrono, pero choca con **R-07** y **ADR-03** en el paso más sensible (`aprobar`): si `ms-adopciones` comitea `APROBADA` antes de que `ms-animales` valide la reserva, ante un `409` tardío igual necesita lógica de compensación —y además ya habrá respondido `200` al Coordinador.
  - **Saga ligera sobre PostgreSQL (c)** reutiliza el stack existente (esquemas versionados con Flyway de ADR-08, `RestClient` + `CircuitBreaker` de ADR-16 y el patrón `@Scheduled` de `TareaVencimientos`) **sin introducir Kafka ni RabbitMQ**, preserva intacto el contrato HTTP síncrono, saca la llamada HTTP de las transacciones JDBC y respeta la naturaleza compensable o terminal de cada transición en `EstadoAnimal`.
- **Diagrama de secuencia (PlantUML):** [Secuencia-aprobar-saga.puml](diagramas/Secuencia-aprobar-saga.puml).
- **Evidencia y piezas existentes consideradas:** `SolicitudService` (`conCambioDeAnimal`, `vencerEntrega`), `TareaVencimientos`, `Animal.cambiarEstadoPorProceso`, `Animal.marcarNoDisponible` (R-13), `EstadoAnimal.TRANSICIONES_DEL_PROCESO`, `docs/diagramas/Secuencia-aprobar.puml`, ADR-02, ADR-03, ADR-06, ADR-15 y ADR-16.

## ADR-18. Idempotencia opcional en `POST /api/donaciones` con `Idempotency-Key` y conflicto `409`

- **Contexto:** `POST /api/donaciones` crea una preferencia de cobro en Mercado Pago y persiste una donación `PENDIENTE`. Ante un timeout de red o un doble clic del cliente, reintentar el `POST` sin idempotencia genera dos preferencias de cobro y dos donaciones duplicadas.
- **Alternativas:** (a) sin idempotencia; (b) `Idempotency-Key` global y `422 Unprocessable Entity` cuando la clave se reutiliza con distinto cuerpo (borrador IETF `draft-ietf-httpapi-idempotency-key-header`); (c) `Idempotency-Key` opcional acotada por `(donante_id, idempotency_key)` con hash SHA-256 canónico del monto/moneda y respuesta `409 Conflict` ante discrepancia de cuerpo.
- **Decisión:** (c).
- **Justificación:**
  - **Opcional y retrocompatible:** si el cliente no envía `Idempotency-Key`, el endpoint conserva exactamente el comportamiento previo (`CONTRATOS.md`).
  - **Aislamiento por donante (`UNIQUE (donante_id, idempotency_key)`):** impide que un usuario reutilice o adivine la clave de otro donante (evita fugas IDOR y bloqueos cruzados).
  - **Por qué `409 Conflict` en lugar de `422`:** el contrato de errores del proyecto (`ErrorResponse`, `GlobalExceptionHandler`) utiliza `400` para errores de validación de entrada y `409` para todo conflicto de estado/regla/concurrencia; responder `409` mantiene un vocabulario HTTP uniforme sin introducir un código nuevo.
  - **Normalización del monto:** el hash se calcula sobre `monto.stripTrailingZeros().toPlainString() + "|" + moneda`, por lo que `500` y `500.00` se reconocen como el mismo cuerpo. Si dos peticiones concurrentes llegan con la misma clave, la restricción `uk_donacion_donante_idempotency_key` decide y el reintento devuelve la donación ganadora.
  - **Sin exposición de campos internos:** ni `idempotencyKey`, ni `requestHash`, ni `referencia`/`preferenciaId`/`pagoId` se serializan en `DonacionResponse`.
- **Evidencia:** `V2__idempotency_key.sql`, `Donacion`, `DonacionService.crear`, `DonacionController.crear`, `DonacionIdempotenciaTest`, `FlywayMigrationIT` (ms-donaciones).

## ADR-19. Política de versionado de la API REST (`/api`)

- **Contexto:** los endpoints públicos viven bajo `/api/animales/**`, `/api/adoptantes/**`, `/api/solicitudes/**` y `/api/donaciones/**` sin prefijo de versión en la ruta (`CONTRATOS.md`), y el proyecto evolucionó agregando paginación (`page`, `size`, `X-Total-Count`) e idempotencia (`Idempotency-Key`) sin romper clientes existentes.
- **Alternativas:** (a) migrar ya a `/api/v1/**` (rompe colección Postman, `prueba-humo.sh` y clientes existentes); (b) versionado por `Accept` header (media types custom, mayor fricción en Swagger/Postman/Gateway); (c) mantener `/api/**` como v1 estable con evolución estrictamente aditiva y reservar `/api/v2/**` únicamente ante un *breaking change* inevitable.
- **Decisión:** (c).
- **Reglas de evolución:**
  - **Cambios aditivos permitidos bajo `/api/**` (retrocompatibles):** nuevos campos opcionales en requests, nuevos campos en responses JSON, nuevos parámetros de consulta opcionales con default seguro (`page`, `size`), nuevos encabezados opcionales de entrada o salida (`Idempotency-Key`, `X-Total-Count`), y nuevos endpoints o recursos.
  - **Cambios incompatibles (*breaking changes*, prohibidos en `/api/**`):** eliminar o renombrar rutas o campos, volver obligatorio un parámetro/campo opcional, cambiar el tipo o formato raíz de una respuesta (por ejemplo envolver un JSON array en un objeto paginado), o modificar códigos HTTP/semántica de errores documentados.
  - **Introducción de `/api/v2/**`:** cuando un cambio incompatible sea inevitable, se publicará en paralelo bajo `/api/v2/**` (enrutado por el Gateway) manteniendo `/api/**` con cabeceras `Deprecation` y `Sunset` durante una ventana de migración documentada, y exportando las especificaciones OpenAPI versionadas en `docs/openapi/`.
- **Evidencia:** `CONTRATOS.md`, `docs/openapi/*.json`, `AnimalPaginacionTest`, `SolicitudPaginacionTest`, `DonacionPaginacionTest`, `DonacionIdempotenciaTest`.

## ADR-20. Virtual threads, reintentos selectivos en GET idempotentes, transiciones idempotentes en Animales y ShedLock JDBC

- **Contexto:** con el bloqueo de `ServiceTokenProvider` ya libre de `synchronized` (ADR-15) y circuit breakers activos (ADR-16), quedaban cuatro brechas de concurrencia y resiliencia: (1) `spring.threads.virtual.enabled` no estaba activo en los servicios MVC; (2) micro-cortes transitorios en consultas `GET` a Georef o Mercado Pago fallaban al primer intento; (3) `PUT /internal/animales/{id}/estado` devolvía `409` si se repetía `RESERVADO -> RESERVADO` tras un timeout de red; (4) `TareaVencimientos` (`@Scheduled`) no tenía bloqueo distribuido ante múltiples réplicas de `ms-adopciones`.
- **Decisión:**
  1. **Virtual threads (Java 21):** activar `spring.threads.virtual.enabled: true` en `ms-animales`, `ms-adopciones` y `ms-donaciones` (no en `gateway`, que es WebFlux reactivo).
  2. **Retry de Resilience4j solo en GET idempotentes:** configurado en `GeorefClient.geolocalizar` (`GET /direcciones`) y `MercadoPagoClient.buscarPagoAprobado` (`GET /v1/payments/search`) dentro del `CircuitBreaker` externo para absorber fallas transitorias sin contaminar la tasa de fallos. **Prohibido reintentar `POST /checkout/preferences`** (`crearCobro`), evitando crear preferencias duplicadas.
  3. **Transiciones internas idempotentes en `ms-animales`:** `CambioEstadoRequest` incorpora `solicitudId` opcional y `animal` agrega `solicitud_activa_id` (`V2__solicitud_id_idempotencia.sql`). Repetir una transición sobre un animal que ya está en el estado destino para la **misma** `solicitudId` (o ya `DISPONIBLE` sin solicitud activa) responde `200 OK` sin efecto ni incremento de `@Version`; otra solicitud sobre un animal con adopción en curso recibe `409`.
  4. **ShedLock sobre JDBC (`adopciones.shedlock`, `V2__shedlock.sql`):** `@SchedulerLock` en `TareaVencimientos.vencerEntregas()` garantiza ejecución única entre réplicas sin sumar Redis ni broker, manteniendo una transacción por solicitud y exponiendo los contadores `adopciones.vencimientos.procesados` y `adopciones.vencimientos.fallidos`.
- **Evidencia:** `V2__solicitud_id_idempotencia.sql`, `Animal.cambiarEstadoPorProceso`, `AnimalIdempotenciaTest`, `V2__shedlock.sql`, `ShedLockConfig`, `TareaVencimientos`, `TareaVencimientosTest`, `ClientesResilienciaTest`, `MercadoPagoResilienciaTest`.

## ADR-21. Cierre de brechas operativas y hardening del Gateway (WS-4)

- **Contexto:** tras la auditoría quedaban cuatro brechas operativas declaradas (controles finales vencidos sin alerta, confirmaciones de Mercado Pago solo bajo demanda, caso residual de ADR-06 sin reconciliación automática, ausencia de métricas/reportes de gestión) y hardening pendiente en el borde del Gateway (CORS, cabeceras de seguridad, límite de tamaño de cuerpo).
- **Decisión:**
  - **F1 (Control final vencido):** columna booleana `control_vencido_alertado` + índice `(estado, fecha_control)` (`V3__control_vencido_alertado.sql`), tarea `TareaControlesVencidos` con `@SchedulerLock` que registra una única alerta en el historial sin mutar el estado `EN_ADAPTACION`, más filtro `?controlVencido=true` exclusivo para `COORDINADOR`.
  - **F2 (Donaciones — webhook + reconciliación):** `POST /api/donaciones/webhooks/mercadopago` público en Gateway y `ms-donaciones` autenticado por HMAC-SHA256 (`x-signature` en tiempo constante) con verificación autoritativa `GET /v1/payments/{id}`, complementado por `TareaReconciliacionDonaciones` con ShedLock (`V3__shedlock.sql` en esquema `donaciones`).
  - **F3 (Reconciliador ADR-06):** `ReconciliadorAdopciones` + `TareaReconciliacionAdopciones` + `POST /api/solicitudes/reconciliacion` (`COORDINADOR`). Auto-repara únicamente transiciones seguras usando la idempotencia con `solicitudId` de WS-3 y reporta conflictos ambiguos sin pisar estados.
  - **F4 y F5 (Reportes y eventos de dominio):** `GET /api/solicitudes/reportes` (`COORDINADOR`) con agregaciones JPQL por estado y tiempo promedio de resolución; eventos en proceso (`ApplicationEventPublisher`: `SolicitudAprobadaEvent`, `EntregaConfirmadaEvent`, `AdopcionConfirmadaEvent`) publicados **tras el commit** y registrados sin PII por `EventosAdopcionListener`.
  - **F6 (Hardening del Gateway):** `GatewayHardeningProperties` tipado y validado (`GATEWAY_CORS_ALLOWED_ORIGINS` vacío/cerrado por defecto, `GATEWAY_MAX_BODY_BYTES=1048576`), cabeceras `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Cache-Control` y `Content-Security-Policy`, y `MaxBodySizeFilter` devolviendo `413` en formato `ErrorResponse`.
- **Evidencia:** `V3__control_vencido_alertado.sql`, `TareaControlesVencidos`, `ReconciliadorAdopciones`, `EventosAdopcionListener`, `V3__shedlock.sql` (`ms-donaciones`), `VerificadorFirmaMercadoPago`, `MercadoPagoWebhookController`, `TareaReconciliacionDonaciones`, `GatewayHardeningProperties`, `MaxBodySizeFilter`, `AdopcionesFuncionalidadesTest`, `DonacionesReconciliacionYWebhookTest`, `GatewayHardeningTest`.

## ADR-22. Endurecimiento de JWT (`azp`/`aud`/*clock skew*), probes Actuator y escaneo continuo (WS-5)

- **Contexto:** la validación JWT por defecto de Spring verificaba firma, `iss` y `exp` con un *clock skew* implícito de 60 s sin restringir cliente (`azp`) ni audiencia (`aud`); además, Actuator no declaraba explícitamente probes `liveness`/`readiness` ni `show-details: never`, y el repositorio no tenía escaneos automatizados de seguridad ni modelo de amenazas documentado.
- **Decisión:**
  - `JwtTokenValidators` (`tpi-common`) + `JwtValidationConfig` y su espejo reactivo `GatewayJwtValidators` (`gateway`) configuran decodificadores perezosos (`withJwkSetUri`, preservando ADR-05) con `JWT_CLOCK_SKEW=30s`, lista blanca de `azp` (`GATEWAY_JWT_ALLOWED_CLIENTS` limita el Gateway solo a clientes públicos de usuario) y audiencia obligatoria opcional (`JWT_REQUIRED_AUDIENCE`).
  - Se añade el cliente público `adopciones-pkce` (*Authorization Code + PKCE S256*, `directAccessGrantsEnabled: false`) en `infra/keycloak/realm-adopciones.json`, manteniendo `tpi-client` documentado solo para demo local.
  - Se habilitan `management.endpoint.health.probes.enabled: true` y `show-details: never` en los 4 servicios, junto con matrices negativas IDOR/RBAC, `.github/dependabot.yml`, `.github/workflows/security.yml` (CodeQL, gitleaks, Trivy FS/imágenes, dependency-review), `SECURITY.md` y `docs/SEGURIDAD.md`.
- **Evidencia:** `JwtTokenValidators`, `JwtValidationConfig`, `GatewayJwtValidators`, `JwtTokenValidatorsTest`, `GatewayJwtValidatorTest`, `AdopcionesSecurityMatrixTest`, `DonacionesSecurityMatrixTest`, `AnimalesSecurityMatrixTest`, `SECURITY.md`, `docs/SEGURIDAD.md`, `.github/workflows/security.yml`.

## ADR-23. Compuertas automatizadas de calidad, arquitectura y cobertura (WS-6)

- **Contexto:** las reglas de arquitectura (límites entre servicios, ausencia de Lombok y `@Value`, separación controlador/repositorio/dominio), la higiene estática y el umbral mínimo de cobertura se verificaban por revisión manual en lugar de fallar el build automáticamente.
- **Decisión:**
  - **Maven Wrapper (`mvnw` / `mvnw.cmd`, modo `only-script` sin binarios JAR en git):** fija Maven `3.9.16` reproducible en cualquier máquina.
  - **`maven-enforcer-plugin`:** exige Java `[21,22)`, Maven `>= 3.9.0` y `dependencyConvergence` en todos los módulos.
  - **Checkstyle (`config/checkstyle/checkstyle.xml`) + SpotBugs (`config/spotbugs/exclude.xml`):** ejecutados en la fase `verify` (manteniendo `package -DskipTests` liviano en Dockerfiles), con exclusiones mínimas y documentadas únicamente para falsos positivos Spring/JPA (`EI_EXPOSE_REP*`, `CT_CONSTRUCTOR_THROW`).
  - **ArchUnit (`ArquitecturaTest` en los 4 servicios):** bloquea acceso directo de controladores a repositorios, dependencias de `..model..` hacia capas web/servicios/clientes, dependencias cruzadas entre microservicios, uso de Lombok y `@Value`.
  - **JaCoCo (`jacoco:check` + `coverage-report`):** exige `>= 80%` de cobertura de líneas en paquetes `*.model` y `*.service`, y genera reporte agregado multi-módulo en `coverage-report/target/site/jacoco-aggregate/`.
- **Evidencia:** `mvnw`, `mvnw.cmd`, `pom.xml`, `config/checkstyle/checkstyle.xml`, `config/spotbugs/exclude.xml`, `coverage-report/pom.xml`, `*/src/test/.../ArquitecturaTest.java`.

## ADR-24 — Apagado ordenado (Graceful Shutdown) y métricas de negocio en Prometheus (WS-8)
- **Contexto:** sin `server.shutdown: graceful`, un reinicio o despliegue puede abortar solicitudes HTTP en curso o dejar transacciones a mitad de camino; además `ms-donaciones` carecía de contadores de creación/confirmación/webhooks y `ms-adopciones` no exponía la distribución instantánea de solicitudes por estado.
- **Decisión:**
  - Activar `server.shutdown: graceful` y `spring.lifecycle.timeout-per-shutdown-phase: 20s` (configurable con `SHUTDOWN_TIMEOUT`) en `gateway`, `ms-animales`, `ms-adopciones` y `ms-donaciones`, acompañado de `stop_grace_period: 30s` en `docker-compose.yml` para superar el timeout de drenaje antes de `SIGKILL`.
  - Exponer contadores `donaciones.creadas`, `donaciones.confirmadas`, `donaciones.webhooks.recibidos` y `donaciones.webhooks.rechazados` en `ms-donaciones`, incrementados solo tras confirmar la transacción (sin duplicar en reintentos idempotentes).
  - Exponer el gauge `adopciones.solicitudes.actuales{estado="..."}` en `ms-adopciones` respaldado por una única consulta `GROUP BY` compartida por los 7 estados con caché configurable (`adopciones.metricas.cache-ttl=5s`, `0s` en pruebas) y degradación segura a `NaN` si la base no responde.
  - Documentar despliegue, probes, scraping Prometheus interno, troubleshooting con `X-Correlation-ID`, ShedLock, backup/restore de PostgreSQL y rotación de secretos en `docs/OPERACION.md`.
- **Evidencia:** `*/src/main/resources/application.yml`, `docker-compose.yml`, `MetricasSolicitudes.java`, `DonacionService.java`, `MercadoPagoWebhookController.java`, `*MetricasTest.java`, `docs/OPERACION.md`.

## ADR-25 — Fijación de imágenes base, metadatos OCI, límites de recursos y pipeline CI/CD completo (WS-9)
- **Contexto:** los Dockerfiles usaban etiquetas flotantes (`maven:3.9-eclipse-temurin-21`, `eclipse-temurin:21-jre`) sin metadatos OCI; `docker-compose.yml` no acotaba CPU/memoria; y `.github/workflows/ci.yml` solo ejecutaba `mvn verify` sin Maven Wrapper, sin las pruebas Testcontainers (`-Pintegration-tests`), sin la verificación E2E del stack ni publicación automatizada de artefactos al etiquetar versiones.
- **Decisión:**
  - Fijar versiones exactas (`maven:3.9.16-eclipse-temurin-21` y `eclipse-temurin:21.0.13.1_2-jre-noble`), etiquetas `org.opencontainers.image.*` (`IMAGE_VERSION`, `VCS_REF`) y `.dockerignore` ajustado para no invalidar capas al editar documentación o scripts.
  - Declarar límites explícitos `deploy.resources.limits` (`cpus`, `memory`) en todos los contenedores de `docker-compose.yml` y `docker-compose.demo.yml`.
  - Restringir Dependabot (`.github/dependabot.yml`) para ignorar saltos *major* fuera de contrato (Java > 21, Spring Boot > 3.5).
  - Estructurar `.github/workflows/ci.yml` con permisos mínimos (`contents: read`), cancelación concurrente solo en PRs, `./mvnw` con reintentos de red y caché Maven con `restore-keys`, cuatro jobs (`verify` con tabla JaCoCo en Step Summary, `integration-tests` con PostgreSQL 17 Testcontainers, `e2e-demo` con Docker Compose + Keycloak + WireMock + `prueba-humo.sh`, y `publish-release` en tags `v*` publicando las 4 imágenes en `ghcr.io` y el GitHub Release con los contratos OpenAPI).
- **Evidencia:** `*/Dockerfile`, `.dockerignore`, `docker-compose.yml`, `docker-compose.demo.yml`, `.github/workflows/ci.yml`, `.github/workflows/security.yml`, `.github/dependabot.yml`.

## ADR-26 — Migraciones Flyway, paginación aditiva y protección interna de Prometheus
- **Contexto:** consolidación de tres decisiones estructurales aplicadas sobre la base inicial: (1) el uso de `ddl-auto: update` no versionaba cambios de esquema ni índices; (2) los listados `GET` sin límite exponían a lecturas descontroladas, pero envolver la respuesta en un objeto `Page` habría roto clientes existentes (`CONTRATOS.md`); (3) exponer métricas de negocio y JVM sin autenticación filtraría información operativa sensible.
- **Decisión:**
  - **Flyway + `ddl-auto: validate`:** cada microservicio versiona su propio esquema (`animales`, `adopciones`, `donaciones`) en `src/main/resources/db/migration/V*.sql` incluyendo índices compuestos y tablas `shedlock`, validado automáticamente contra PostgreSQL 17 real en `FlywayMigrationIT`.
  - **Paginación aditiva (`page=0`, `size=50`, máx. `100` + `X-Total-Count`):** los listados paginan en SQL (`LIMIT`/`OFFSET` combinados con `@BatchSize(50)` para evitar `HHH90003004` y N+1), conservando un arreglo JSON como cuerpo y publicando el total en la cabecera `X-Total-Count`.
  - **Prometheus interno con `ROLE_SERVICIO`:** `/actuator/prometheus` no se enruta en el API Gateway y exige un JWT con rol `SERVICIO` en cada microservicio (`401`/`403` verificados en `*MetricasTest`).
- **Evidencia:** `*/src/main/resources/db/migration/V*.sql`, `*FlywayMigrationIT.java`, `*PaginacionTest.java`, `SolicitudListarQueryCountTest.java`, `*MetricasTest.java`, `docs/rendimiento.md`.
