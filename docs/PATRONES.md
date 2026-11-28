# Patrones de Diseño y Arquitectura Aplicados

Inventario de patrones cuya **intención real** se cumple en el código productivo, seguido de los patrones evaluados y descartados deliberadamente para este alcance.

## 1. Patrones implementados

| Patrón | Categoría | Implementación en el repositorio | Problema que resuelve |
|---|---|---|---|
| **API Gateway** | Microservicios | `gateway/` (`Spring Cloud Gateway WebFlux`) | Punto único de entrada público (`:8080`), validación JWT en el borde, cabeceras de seguridad, límite de payload y bloqueo de `/internal/**` y `/actuator/prometheus`. |
| **Database per Service** (esquema aislado) | Datos / Microservicios | `infra/postgres/init/01-esquemas.sh`, `Flyway` (`V1..V3`), `default_schema` | Aislamiento estricto por permisos SQL (`animales_user`, `adopciones_user`, `donaciones_user`) sobre una sola instancia operativa PostgreSQL 17. |
| **Client Credentials / Token Cache** | Seguridad OAuth2 | `ServiceTokenProvider`, cliente `adopciones-service` | Autenticación máquina a máquina (`ROLE_SERVICIO`) con lectura rápida `volatile`, renovación bajo `ReentrantLock` y precalentamiento fuera de transacciones JDBC (ADR-15). |
| **Adapter** | Estructural (GoF) / Integración | `GeorefClient`, `MercadoPagoClient`, `AnimalesClient` | Aísla el dominio de los contratos JSON y códigos HTTP de proveedores externos, traduciéndolos a modelos propios y excepciones de dominio. |
| **Circuit Breaker** | Resiliencia | `ClientesConfig` + Resilience4j en `GeorefClient`, `AnimalesClient` y `MercadoPagoClient` | Corta llamadas rápidamente (*fail-fast*) cuando un servicio externo acumula fallas de red/5xx, sin contar errores de negocio (`404`/`409`) (ADR-16). |
| **Retry selectivo** | Resiliencia | Resilience4j `Retry` en `GeorefClient.geolocalizar` y `MercadoPagoClient.buscarPagoAprobado` | Absorbe micro-cortes transitorios únicamente en consultas `GET` idempotentes; nunca reintenta `POST` de creación de cobros (ADR-20). |
| **Idempotent Receiver** | Integración (EIP) | `DonacionService.crear` (`Idempotency-Key` + SHA-256), `Animal.cambiarEstadoPorProceso` (`solicitudId`), `VerificadorFirmaMercadoPago` + `uk_donacion_pago_id` | Garantiza que reintentos del cliente, de webhooks o del reconciliador no dupliquen cobros ni fallen ante transiciones ya aplicadas (ADR-18, ADR-20). |
| **Distributed Scheduler Lock** | Concurrencia distribuida | `ShedLockConfig` (`adopciones.shedlock`, `donaciones.shedlock`) + `@SchedulerLock` | Evita ejecuciones duplicadas de tareas `@Scheduled` al escalar múltiples réplicas, usando PostgreSQL sin sumar Redis (ADR-20, ADR-21). |
| **Domain Model rico / Máquina de estados** | Dominio (PoEAA / DDD) | `Animal`, `Solicitud`, `Donacion`, `EstadoAnimal`, `EstadoSolicitud` | Encapsula invariantes y transiciones válidas dentro de las entidades sin exponer *setters* abiertos. |
| **Aggregate** | DDD | `Solicitud` + `EventoSolicitud` + `observaciones` (`@BatchSize(50)`) | Una única raíz controla el ciclo de vida, las columnas de unicidad y la bitácora inmutable de auditoría. |
| **Optimistic Offline Lock** | Concurrencia (PoEAA) | `@Version` en `Animal`, `Solicitud` y `Donacion` + columnas `UNIQUE` (`animal_pendiente`, `adoptante_activo`) | Detecta modificaciones concurrentes devolviendo `409 Conflict` sin recurrir a bloqueos pesimistas largos (ADR-07). |
| **Template Callback transaccional** | Comportamiento | `TransactionTemplate` (`tx` y `txReadOnly`) en `SolicitudService` y `DonacionService` | Delimita con precisión qué pasos corren dentro de una transacción JDBC y mantiene las lecturas HTTP externas fuera de ella. |
| **Domain Events (en proceso)** | Dominio / Desacoplamiento | `SolicitudAprobadaEvent`, `EntregaConfirmadaEvent`, `AdopcionConfirmadaEvent`, `EventosAdopcionListener` | Publica hechos de negocio confirmados tras el `COMMIT` local sin acoplar `SolicitudService` a efectos secundarios (ADR-21). |
| **Correlation Identifier** | Observabilidad (EIP) | `CorrelationIdGlobalFilter`, `CorrelationIdFilter`, `CorrelationIdInterceptor` | Propaga y sanitiza `X-Correlation-ID` en logs MDC y llamadas REST internas de punta a punta. |

---

## 2. Notas de diseño sobre los patrones clave

### Adapter + Resiliencia (`GeorefClient`, `MercadoPagoClient`, `AnimalesClient`)
Cada cliente encapsula timeouts diferenciados (`connect-timeout` / `read-timeout`), `Retry` (solo en `GET`) y `CircuitBreaker` programático (sin proxies AOP, lo que facilita pruebas deterministas contra servidores HTTP embebidos en `ClientesResilienciaTest` y `MercadoPagoResilienciaTest`). El resto del dominio nunca ve estructuras JSON de terceros ni `RestClientException`.

### Consistencia local-primero + Reconciliación compensatoria (`SolicitudService` + `ReconciliadorAdopciones`)
Para coordinar `ms-adopciones` y `ms-animales` sin transacciones distribuidas 2PC ni broker externo:
1. Se precalienta el token OAuth2 fuera de la transacción (ADR-15).
2. Se muta `Solicitud` y se ejecuta `saveAndFlush` local para detectar colisiones `@Version` / `UNIQUE` antes de salir por red.
3. Se invoca `PUT /internal/animales/{id}/estado` pasando `solicitudId` (idempotente). Si `ms-animales` responde `409` o `503`, la transacción local revierte automáticamente.
4. Si ocurriera una caída exactamente entre el `200 OK` remoto y el `COMMIT` local, `ReconciliadorAdopciones` detecta y repara los desajustes seguros de forma periódica o manual (`POST /api/solicitudes/reconciliacion`).

---

## 3. Patrones evaluados y descartados deliberadamente

| Patrón descartado | Motivo técnico |
|---|---|
| **2PC / Transacciones distribuidas XA** | No aplicable sobre servicios REST independientes ni deseable por acoplamiento y bloqueo de recursos. |
| **Transactional Outbox + Broker externo (Kafka / RabbitMQ)** | Sobredimensionado para el volumen operativo de un refugio y choca con la validación síncrona `R-07` al aprobar (ver análisis completo en [ADR-17](ADR.md#adr-17-resolución-de-la-inconsistencia-residual-del-adr-06-estado-actual-vs-outbox-vs-saga-con-compensación)). Si en el futuro aparecen consumidores asíncronos externos, los eventos en proceso (`ApplicationEventPublisher`) ya dejan preparado el punto de extensión. |
| **Strategy con jerarquía de interfaces para evaluación** | Las reglas `R-04`, `R-05` y `R-06` son fijas del dominio y no se intercambian dinámicamente en tiempo de ejecución; métodos cohesivos en `EvaluadorSolicitud` resultan más simples y legibles. |
| **Factory Method (GoF con subclases)** | `Solicitud.pendiente(...)` y `Solicitud.rechazadaPorIncompatibilidad(...)` son *static factory methods* (Effective Java) expresivos; no existen subtipos polimórficos de `Solicitud`. |
