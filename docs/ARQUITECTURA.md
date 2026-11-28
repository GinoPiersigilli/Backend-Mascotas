# Arquitectura del Sistema

Documento de referencia sobre la topología de contenedores, fronteras de confianza, modelo de consistencia entre servicios, persistencia aislada y pipeline de verificación continua.

---

## 1. Topología de contenedores y fronteras de red

En el perfil productivo base (`docker-compose.yml`), **únicamente el API Gateway (`:8080`) y Keycloak (`:8180`) publican puertos al host**. Los tres microservicios de dominio y las dos instancias PostgreSQL operan aislados dentro de la red interna `tpi-net`.

```mermaid
flowchart LR
    subgraph Exterior["Clientes y Proveedores Externos"]
        CLI["Cliente / Frontend / Postman"]
        MP_WH["Mercado Pago Webhooks"]
        GEOREF["API Georef Argentina (HTTPS)"]
        MP_API["API Mercado Pago (HTTPS)"]
    end

    subgraph Borde["Puertos publicados"]
        GW["gateway :8080 (Spring Cloud Gateway WebFlux)"]
        KC["keycloak :8180/:8080 (OIDC / JWT RS256)"]
    end

    subgraph RedInterna["Red interna tpi-net (sin puertos publicados en prod)"]
        ANI["ms-animales :8081 (MVC + Virtual Threads)"]
        ADO["ms-adopciones :8082 (MVC + Virtual Threads + ShedLock)"]
        DON["ms-donaciones :8083 (MVC + Virtual Threads + ShedLock)"]
        PG["postgres :5432 (PG 17: esquemas animales | adopciones | donaciones)"]
        KCDB["keycloak-db :5432 (PG 17 dedicado)"]
    end

    CLI -->|"1. OIDC Token"| KC
    CLI -->|"2. HTTPS/HTTP + Bearer JWT"| GW
    MP_WH -->|"POST /api/donaciones/webhooks/mercadopago (x-signature HMAC)"| GW

    GW -->|"/api/animales/**"| ANI
    GW -->|"/api/adoptantes/**, /api/solicitudes/**"| ADO
    GW -->|"/api/donaciones/**"| DON

    ADO -->|"/internal/animales/** (JWT ROLE_SERVICIO)"| ANI
    ADO -->|"client_credentials"| KC
    ADO -->|"GET /direcciones (CB + Retry)"| GEOREF
    DON -->|"POST preferences (CB) / GET payments (CB + Retry)"| MP_API

    ANI -->|"animales_user"| PG
    ADO -->|"adopciones_user"| PG
    DON -->|"donaciones_user"| PG
    KC --> KCDB
```

> Diagramas C4 originales en PlantUML / SVG también disponibles en [`docs/diagramas/`](diagramas/).

---

## 2. Responsabilidades por módulo Maven

| Módulo | Stack | Rol arquitectónico |
|---|---|---|
| `gateway` | WebFlux + Netty + OAuth2 Resource Server reactivo | Autenticación JWT en el borde (`iss`, `exp` skew 30 s, allowlist `azp`), CORS cerrado por defecto, cabeceras de seguridad, límite de cuerpo (`413`), generación/sanitización de `X-Correlation-ID` y ocultamiento de `/internal/**` y `/actuator/prometheus`. No depende de `tpi-common` (ADR-09). |
| `tpi-common` | Librería JAR (`provided` deps) | Contrato unificado `ErrorResponse`, `GlobalExceptionHandler`, manejadores JSON `401`/`403`, `KeycloakRoleConverter`, `JwtTokenValidators`, `ResourceServerDefaults` y propagación de `X-Correlation-ID` en `Servlet` y `RestClient`. |
| `ms-animales` | Spring MVC + JPA + Flyway | Único dueño del catálogo y de la máquina de estados `EstadoAnimal` (`R-13`). Expone API pública (`/api/animales/**`) e interna idempotente (`/internal/animales/**`, restringida a `ROLE_SERVICIO`). |
| `ms-adopciones` | Spring MVC + JPA + Flyway + Resilience4j + ShedLock | Orquesta perfiles, evaluación automática (`R-01..R-06` con Georef + Haversine), aprobación/entrega/control/devolución (`R-07..R-12`), eventos en proceso post-commit, reportes y tareas distribuidas (vencimientos, alertas de control y reconciliación con `ms-animales`). |
| `ms-donaciones` | Spring MVC + JPA + Flyway + Resilience4j + ShedLock | Gestiona donaciones (`D-01..D-04`) con `Idempotency-Key` SHA-256, creación de preferencias Checkout Pro y triple vía idempotente de confirmación (webhook firmado HMAC-SHA256, reconciliación periódica y consulta bajo demanda). |
| `coverage-report` | Módulo POM agregador | Consolida la cobertura multi-módulo (`jacoco:report-aggregate`) durante `verify`. |

---

## 3. Consistencia entre servicios sin transacciones distribuidas (P2–P4)

Al no existir 2PC sobre HTTP ni un broker externo, las transiciones que modifican tanto `Solicitud` (`ms-adopciones`) como `Animal` (`ms-animales`) combinan **orden local-primero con rollback**, **idempotencia por `solicitudId`** y **reconciliación periódica/manual** (ADR-06, ADR-15, ADR-20, ADR-21):

```mermaid
sequenceDiagram
    autonumber
    actor Coord as Coordinador
    participant GW as gateway
    participant ADO as ms-adopciones
    participant PG_A as PG (esquema adopciones)
    participant ANI as ms-animales
    participant PG_N as PG (esquema animales)

    Coord->>GW: POST /api/solicitudes/{id}/aprobar (JWT COORDINADOR)
    GW->>ADO: Reenvía + X-Correlation-ID
    Note over ADO: 1) Precalienta token SERVICIO fuera de Tx (ADR-15)
    ADO->>PG_A: BEGIN + aprobar() + saveAndFlush() (@Version / UNIQUE)
    ADO->>ANI: PUT /internal/animales/{animalId}/estado {RESERVADO, solicitudId}
    ANI->>PG_N: Transición idempotente por solicitudId + COMMIT
    alt ms-animales responde 200 OK
        ANI-->>ADO: 200 AnimalDto (RESERVADO)
        ADO->>PG_A: COMMIT + publica SolicitudAprobadaEvent
        ADO-->>GW: 200 SolicitudResponse (APROBADA)
    else ms-animales responde 409 o 503 / timeout
        ANI-->>ADO: 409 / 503
        ADO->>PG_A: ROLLBACK (nunca queda APROBADA sin reservar)
        ADO-->>GW: 409 / 503 ErrorResponse
    end
    Note over ADO,ANI: Caso borde (caída tras 200 remoto antes del COMMIT local): ReconciliadorAdopciones repara o alerta
```

---

## 4. Flujo idempotente de donaciones y webhook firmado (P5)

```mermaid
sequenceDiagram
    autonumber
    actor Don as Adoptante / Donante
    participant GW as gateway
    participant DON as ms-donaciones
    participant PG_D as PG (esquema donaciones)
    participant MP as Mercado Pago API

    Don->>GW: POST /api/donaciones (Idempotency-Key, monto)
    GW->>DON: Reenvía + JWT ADOPTANTE
    DON->>PG_D: Consulta (donante_id, idempotency_key) en Tx readOnly
    alt Clave nueva
        DON->>MP: POST /checkout/preferences (fuera de Tx, sin Retry)
        MP-->>DON: 201 {preferenciaId, init_point}
        DON->>PG_D: INSERT PENDIENTE + SHA-256 hash (COMMIT)
        DON-->>GW: 201 DonacionResponse (linkPago)
    else Misma clave + mismo monto
        DON-->>GW: 201 DonacionResponse existente (sin duplicar cobro)
    else Misma clave + distinto monto
        DON-->>GW: 409 Conflict
    end

    MP->>GW: POST /api/donaciones/webhooks/mercadopago (x-signature HMAC-SHA256)
    GW->>DON: Ruta pública explícita
    DON->>DON: Verifica HMAC en tiempo constante (MP_WEBHOOK_SECRET)
    DON->>MP: GET /v1/payments/{data.id} (CB + Retry)
    MP-->>DON: 200 {status: "approved", external_reference}
    DON->>PG_D: UPDATE PENDIENTE -> CONFIRMADA (uk_donacion_pago_id)
    DON-->>GW: 200 {"confirmada": true, "resultado": "CONFIRMADA"}
```

---

## 5. Aislamiento de datos y control de concurrencia

- **Aislamiento por esquema y rol de base de datos:** el script `infra/postgres/init/01-esquemas.sh` revoca privilegios cruzados; cada microservicio solo puede leer y migrar su propio esquema (`animales`, `adopciones`, `donaciones`) con `Flyway` + `ddl-auto: validate`.
- **Invariantes concurrentes en PostgreSQL:**
  - `adopciones.solicitud`: `uk_solicitud_animal_pendiente` (`R-01`), `uk_solicitud_adoptante_activo` (`R-02`) y `@Version` optimista (`SolicitudConcurrenciaTest` demuestra `1×200 + 1×409` ante dos aprobaciones simultáneas).
  - `donaciones.donacion`: `uk_donacion_pago_id` (`D-03`) y `uk_donacion_donante_idempotency_key` (`ADR-18`).
  - `adopciones.shedlock` y `donaciones.shedlock`: exclusión mutua entre réplicas para tareas `@Scheduled`.
- **Eficiencia de consultas:** `spring.jpa.open-in-view: false`, `@BatchSize(size = 50)` y paginación SQL real (`LIMIT`/`OFFSET` + `X-Total-Count`), respaldada por índices medidos con 10.000 filas en [`docs/rendimiento.md`](rendimiento.md).

---

## 6. Pipeline de calidad, seguridad y entrega continua

```mermaid
flowchart LR
    PR["Push / Pull Request"] --> V["verify: Enforcer + 237 Tests + ArchUnit + Checkstyle + SpotBugs + JaCoCo >=80%/85%"]
    PR --> IT["integration-tests: Testcontainers PostgreSQL 17 + Flyway V1..V3"]
    PR --> E2E["e2e-demo: Compose 8 contenedores + Keycloak + WireMock + prueba-humo.sh"]
    PR --> SEC["security.yml: CodeQL + gitleaks + Trivy FS & 4 imágenes + Dependency Review"]
    V & IT & E2E -->|"Tag v*"| REL["publish-release: Multi-stage Buildx -> GHCR + GitHub Release (OpenAPI JSON)"]
```
