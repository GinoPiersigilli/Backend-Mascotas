# Registro de decisiones arquitectónicas (ADR)

Formato: contexto → alternativas → decisión → consecuencias → evidencia. Estado de todas: **aceptada**
(sujeta a revisión del equipo en un Merge Request).

---

## ADR-01. Tres servicios separados por dueño de la información

- **Contexto:** RC-03 exige ≥ 2 servicios con responsabilidades coherentes, no dos CRUD.
- **Alternativas:** (a) un monolito modular; (b) Animales + Adopciones (Donaciones dentro de Adopciones); (c) tres servicios; (d) un servicio por entidad (perfil, solicitud…).
- **Decisión:** (c). Animales es dueño del estado del animal; Adopciones, del proceso; Donaciones, del ciclo de pago.
- **Justificación:** dueños de datos distintos (el animal existe sin solicitudes), ritmos de cambio distintos, reglas distintas (Animales protege la coherencia del estado; Adopciones, la del proceso). Donaciones tiene datos, reglas y proveedor propios: cambiar de plataforma de pagos no toca a los otros. (d) multiplicaría llamadas sin reglas nuevas.
- **Consecuencias:** Donaciones no colabora con otro servicio (punto a defender: la colaboración exigida la cubre la adopción). Animales es chico pero tiene una máquina de estados que rechaza transiciones.
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
- **Consecuencias:** nunca queda una solicitud APROBADA con el animal sin reservar. Caso residual: Animales confirma y el *commit* local falla (por ejemplo, caída de la base en ese instante); quedaría el animal RESERVADO sin solicitud APROBADA. Se registra en logs y se corrige manualmente; una mejora sería una tarea de reconciliación.
- **Evidencia:** `SolicitudService.conCambioDeAnimal`, pruebas `r07SiAnimalesRechazaLaReservaNoSeAprueba` y `siAnimalesNoRespondeAlAprobar...`.

## ADR-07. Concurrencia: columnas de bloqueo con UNIQUE + bloqueo optimista

- **Contexto:** R-01 y R-02 ante dos peticiones simultáneas (riesgo señalado en el dominio §13).
- **Alternativas:** índice parcial de PostgreSQL (`WHERE estado='PENDIENTE'`), bloqueo pesimista, verificación solo en memoria.
- **Decisión:** columnas `animal_pendiente` y `adoptante_activo` que la entidad completa según el estado (null si no aplica) con restricción UNIQUE; más `@Version` en `Solicitud`, `Animal` y `Donacion`.
- **Justificación:** funciona con `ddl-auto` (contrato) y con H2 en las pruebas; PostgreSQL admite múltiples NULL en UNIQUE.
- **Evidencia:** `Solicitud` (Javadoc y `cambiarEstado`), `SolicitudRepositoryTest`.

## ADR-08. Persistencia: una instancia, esquema y usuario por servicio; Keycloak aparte

- **Alternativas:** una base por servicio (más contenedores), esquemas compartidos con un solo usuario (aislamiento débil).
- **Decisión:** base `tpi` con esquemas `animales`, `adopciones`, `donaciones`, cada uno propiedad de su usuario; Keycloak en `keycloak-db`.
- **Consecuencias:** aislamiento real por permisos con un solo contenedor de negocio; Keycloak se recrea sin tocar datos de negocio. `ddl-auto: update` simplifica pero no versiona el esquema (mejora: Flyway).
- **Evidencia:** `infra/postgres/init/01-esquemas.sh`, `hibernate.default_schema`.

## ADR-09. Componente común: qué se comparte y qué no

- **Se comparte** (igual en los tres servicios): `ErrorResponse`, excepciones, `GlobalExceptionHandler`, 401/403 en JSON, `KeycloakRoleConverter`, `ResourceServerDefaults`, `Roles`, correlación.
- **No se comparte:** entidades, DTO, reglas y rutas de seguridad de cada servicio; los estados del animal que usa Adopciones son una copia local (`adopciones.client.EstadoAnimal`): son contrato HTTP, no modelo compartido.
- **Distribución:** `mvn install` al `.m2`; dependencias Spring en `provided`. Los Dockerfiles repiten ese mecanismo dentro de la imagen.
- **Gateway:** no depende del JAR (reactivo); repite 15 líneas del conversor de roles a propósito.

## ADR-10. Georef como fuente de ubicación y distancia en línea recta

- **Alternativas:** OSRM (requiere desplegar y procesar el mapa; dudoso como “API pública por Internet”), Google Distance Matrix (clave y costos).
- **Decisión:** Georef `/direcciones` + Haversine propio; falla externa → `distanciaNoVerificada` (no bloquea: la decisión es humana).
- **Evidencia:** `GeorefClient` (formato verificado contra la API real), `CalculadoraDistancia`, `EvaluadorSolicitud`.

## ADR-11. Mercado Pago sin webhooks

- **Decisión:** Checkout Pro (preferencia + `init_point`); la confirmación se obtiene consultando `/v1/payments/search` por `external_reference` (UUID propio) al consultar la donación.
- **Consecuencias:** sin endpoint público para notificaciones (no hay HTTPS público en entorno local); una donación puede quedar PENDIENTE hasta que alguien la consulte.
- **Evidencia:** `MercadoPagoClient`, `DonacionService.obtener`.

## ADR-12. Sin Lombok; records para DTO

- **Contexto:** Lombok introduce un procesador de anotaciones acoplado a la versión del compilador (distintos entornos JDK).
- **Decisión:** records para DTO y configuración; entidades con getters explícitos y métodos de dominio en lugar de setters.
- **Consecuencia:** algo más de código, pero las entidades no exponen setters que permitan saltear las reglas.

## ADR-13. Pruebas sin infraestructura externa

- **Decisión:** H2 en modo PostgreSQL, `MockRestServiceServer` con respuestas grabadas de Georef y documentadas de Mercado Pago, `@MockitoBean` para clientes en las pruebas de flujo, reloj inyectable (`Clock`) para plazos, `jwt()`/`mockJwt()` de Spring Security Test.
- **Consecuencia:** CI reproducible sin Docker ni Internet; no prueban el dialecto real de PostgreSQL ni Keycloak (lo cubre la prueba de humo con Docker).

## ADR-14. Docker: construcción en dos etapas desde la raíz

- **Decisión:** cada Dockerfile instala POM padre y `tpi-common` en el `.m2` de la imagen y compila el servicio; imagen final `eclipse-temurin:21-jre`. Healthchecks sin `curl` (bash `/dev/tcp`); `depends_on` con `service_healthy`; logging con rotación.

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
