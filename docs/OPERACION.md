# Runbook de Operación y Observabilidad

Guía operativa para desplegar, monitorear, diagnosticar, respaldar y rotar credenciales del backend.

---

## 1. Modos de despliegue y arranque

### 1.1 Desarrollo local (con Swagger y puertos de servicios expuestos)
```bash
cp .env.example .env
# Editar .env con contraseñas locales y, si se usa sandbox real, MP_ACCESS_TOKEN
docker compose up --build -d --wait
```
Aplica automáticamente `docker-compose.yml` + `docker-compose.override.yml` (habilita `SWAGGER_ENABLED=true` y expone `:8081–:8083` y `:5433`).

### 1.2 Demostración reproducible sin credenciales externas (WireMock)
```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build -d --wait
EXPECT_DONACIONES=true ./scripts/prueba-humo.sh
```
Sustituye Georef y Mercado Pago por el contenedor `tpi-wiremock` con stubs en `infra/wiremock/mappings/` y fija `PERIODO_ADAPTACION_DIAS=0` para recorrer el ciclo P1–P5 completo sin acceso a Internet.

### 1.3 Perfil base (sin override de desarrollo)
```bash
docker compose -f docker-compose.yml up --build -d --wait
```
Solo expone el API Gateway (`:8080`) y Keycloak (`:8180`). Los microservicios (`:8081–:8083`) y las bases de datos quedan aislados en la red interna `tpi-net` con `SWAGGER_ENABLED=false`.

---

## 2. Healthchecks, probes y apagado ordenado (Graceful Shutdown)

Todos los servicios (`gateway`, `ms-animales`, `ms-adopciones`, `ms-donaciones`) exponen probes compatibles con Kubernetes y Docker Compose con `management.endpoint.health.show-details: never` (sin filtrar detalles internos):

| Endpoint | Uso |
|---|---|
| `GET /actuator/health` | Estado agregado (`UP`/`DOWN`), usado por `healthcheck` de Docker Compose. |
| `GET /actuator/health/liveness` | Indica si la JVM/proceso sigue vivo (`LivenessState`). |
| `GET /actuator/health/readiness` | Indica si el servicio puede recibir tráfico (`ReadinessState`). |

### Apagado ordenado
- Cada aplicación tiene configurado `server.shutdown: graceful` y `spring.lifecycle.timeout-per-shutdown-phase: 20s` (ajustable con `SHUTDOWN_TIMEOUT`).
- En `docker-compose.yml`, los 4 servicios fijan `stop_grace_period: 30s` (por encima de los `20s` de Spring) para que Docker no envíe `SIGKILL` a los 10 s por defecto.
- Al recibir `SIGTERM`, el servidor deja de aceptar nuevas conexiones, drena las peticiones HTTP en vuelo y cierra limpiamente los pools HikariCP y las tareas programadas antes de terminar.

---

## 3. Métricas y scraping Prometheus

Los 3 microservicios (`ms-animales:8081`, `ms-adopciones:8082`, `ms-donaciones:8083`) exponen `GET /actuator/prometheus` **exclusivamente en la red interna `tpi-net`** y protegido con rol `SERVICIO`. El API Gateway no tiene ruta hacia `/actuator/prometheus`.

### Consulta manual desde la red interna
```bash
# 1) Obtener un token client_credentials (rol SERVICIO)
TOKEN=$(docker compose exec -T ms-adopciones bash -c '
  curl -s -X POST http://keycloak:8080/realms/adopciones/protocol/openid-connect/token \
    -d grant_type=client_credentials \
    -d client_id=adopciones-service \
    -d client_secret="$ADOPCIONES_SERVICE_CLIENT_SECRET"
' | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

# 2) Consultar el scrape de cualquier microservicio por la red interna
docker compose exec -T gateway bash -c \
  "curl -s -H 'Authorization: Bearer $TOKEN' http://ms-adopciones:8082/actuator/prometheus | grep '^adopciones_'"
```

### Catálogo de métricas de negocio

Todas las métricas llevan la etiqueta común `application` (`ms-animales`, `ms-adopciones`, `ms-donaciones`) además de las métricas estándar de JVM, HTTP, HikariCP y Resilience4j (`resilience4j_circuitbreaker_state`, `resilience4j_retry_calls_total`).

| Servicio | Métrica Prometheus | Tipo | Significado operativo |
|---|---|---|---|
| `ms-adopciones` | `adopciones_solicitudes_creadas_total` | Counter | Total de solicitudes creadas (P2). |
| `ms-adopciones` | `adopciones_solicitudes_aprobadas_total` | Counter | Total de solicitudes aprobadas tras confirmar reserva en `ms-animales`. |
| `ms-adopciones` | `adopciones_solicitudes_actuales{estado="..."}` | Gauge | Cantidad actual de solicitudes por `EstadoSolicitud` (una única consulta `GROUP BY` cacheada `ADOPCIONES_METRICAS_CACHE_TTL`, 5 s por defecto). |
| `ms-adopciones` | `adopciones_vencimientos_procesados_total` / `_fallidos_total` | Counter | Entregas vencidas canceladas (R-09) y errores transitorios en la tarea. |
| `ms-adopciones` | `adopciones_controles_vencidos_alertados_total` / `_fallidos_total` | Counter | Alertas de control final vencido registradas (F1) y errores. |
| `ms-adopciones` | `adopciones_reconciliacion_reparadas_total` / `_conflictos_total` | Counter | Desajustes ADR-06 reparados automáticamente vs. conflictos ambiguos (F3). |
| `ms-donaciones` | `donaciones_creadas_total` | Counter | Donaciones creadas en `PENDIENTE` (no cuenta reintentos idempotentes ni fallas 503 previas). |
| `ms-donaciones` | `donaciones_confirmadas_total` | Counter | Donaciones que pasaron a `CONFIRMADA` (consulta, webhook o reconciliación). |
| `ms-donaciones` | `donaciones_webhooks_recibidos_total` | Counter | Webhooks de Mercado Pago aceptados con firma HMAC-SHA256 válida. |
| `ms-donaciones` | `donaciones_webhooks_rechazados_total` | Counter | Webhooks rechazados con `401` por firma ausente o inválida. |
| `ms-donaciones` | `donaciones_reconciliacion_confirmadas_total` / `_fallidas_total` | Counter | Confirmaciones y errores de la tarea programada de reconciliación (F2). |

---

## 4. Troubleshooting con `X-Correlation-ID`

Cada respuesta del API Gateway devuelve la cabecera `X-Correlation-ID` (reutilizando la enviada por el cliente si cumple `^[A-Za-z0-9._-]{1,100}$`, o generando un UUID). El identificador se propaga al MDC reactivo del Gateway, a los microservicios y a las llamadas internas `ms-adopciones → ms-animales` mediante `CorrelationIdInterceptor`.

### Seguimiento extremo a extremo en logs
```bash
# Reemplazar <CID> por el X-Correlation-ID devuelto en la respuesta HTTP
docker compose logs --since 15m gateway ms-adopciones ms-animales ms-donaciones | grep "<CID>"
```
Formato de log unificado: `yyyy-MM-dd HH:mm:ss LEVEL [servicio] [correlationId] logger - mensaje`.

### Diagnóstico rápido de fallos frecuentes

| Síntoma | Dónde mirar | Acción |
|---|---|---|
| `401` inesperado con token recién emitido | `docker compose logs gateway ms-adopciones \| grep "<CID>"` | Verificar que `KEYCLOAK_ISSUER_URI` coincida exactamente con el claim `iss`, que `azp` esté en `GATEWAY_JWT_ALLOWED_CLIENTS` / `JWT_ALLOWED_CLIENTS` y que el reloj del host no exceda `JWT_CLOCK_SKEW` (`30s`). |
| `503` al presentar/aprobar solicitud o donar | Métrica `resilience4j_circuitbreaker_state` y logs de `AnimalesClient` / `MercadoPagoClient` | Confirmar estado del servicio aguas abajo o credencial `MP_ACCESS_TOKEN`. Si el circuito está `OPEN`, volverá a `HALF_OPEN` automáticamente tras `wait-duration-in-open-state` (15 s Animales / 30 s Georef y Mercado Pago). |
| `distanciaNoVerificada: true` en solicitudes | Logs de `GeorefClient` (`[ms-adopciones]`) | Georef externo falló o agotó los 3 reintentos. El sistema degrada sin bloquear (R-06); el Coordinador decide manualmente. |
| Aumento en `donaciones_webhooks_rechazados_total` | Logs de `VerificadorFirmaMercadoPago` | Verificar que `MP_WEBHOOK_SECRET` coincida con el panel de Webhooks de Mercado Pago (o posible intento de suplantación). |
| Aumento en `adopciones_reconciliacion_conflictos_total` | `POST /api/solicitudes/reconciliacion` (rol `COORDINADOR`) | Inspeccionar la lista `detalles` del informe: indica un desajuste no trivial entre el estado de la solicitud y el estado del animal que requiere revisión manual. |

---

## 5. Tareas programadas (ShedLock) y reconciliación

Las tareas periódicas de `ms-adopciones` (vencimiento de entregas, alertas de control final, reconciliación con `ms-animales`) y `ms-donaciones` (reconciliación de pagos pendientes) usan **ShedLock sobre JDBC** (`adopciones.shedlock` y `donaciones.shedlock`), por lo que pueden escalarse varias réplicas sin duplicar ejecuciones.

### Inspeccionar bloqueos activos en PostgreSQL
```bash
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "SELECT 'adopciones' AS esquema, name, lock_until, locked_at, locked_by FROM adopciones.shedlock
   UNION ALL
   SELECT 'donaciones' AS esquema, name, lock_until, locked_at, locked_by FROM donaciones.shedlock;"
```

### Disparar reconciliación manual de adopciones (ADR-06 / F3)
```bash
curl -s -X POST http://localhost:8080/api/solicitudes/reconciliacion \
  -H "Authorization: Bearer $TOKEN_COORDINADOR"
```
Repara automáticamente los desajustes seguros (por ejemplo, animal que quedó `DISPONIBLE` pese a existir una solicitud `APROBADA` con su `solicitudId`) y reporta cualquier conflicto ambiguo sin sobreescribirlo.

---

## 6. Backup y restore de PostgreSQL

Hay dos instancias PostgreSQL 17 con volúmenes dedicados:
- `tpi-postgres` (`tpi-postgres-data`): base de negocio con 3 esquemas (`animales`, `adopciones`, `donaciones`).
- `tpi-keycloak-db` (`tpi-keycloak-db-data`): base del realm de Keycloak.

> **Advertencia:** `docker compose down -v` **elimina ambos volúmenes**. Usar `docker compose down` (sin `-v`) para detener contenedores preservando datos.

### Backup (formato custom comprimido `-Fc`)
```bash
mkdir -p backups
FECHA=$(date +%Y%m%d_%H%M%S)

# Base de negocio completa (los 3 esquemas)
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc \
  > "backups/tpi_negocio_${FECHA}.dump"

# O respaldo de un único esquema (ejemplo: adopciones)
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -n adopciones -Fc \
  > "backups/tpi_adopciones_${FECHA}.dump"

# Base de Keycloak
docker compose exec -T keycloak-db pg_dump -U "$KC_DB_USER" -d keycloak -Fc \
  > "backups/tpi_keycloak_${FECHA}.dump"
```

### Verificación del archivo de backup
```bash
docker compose exec -T postgres pg_restore --list < "backups/tpi_negocio_${FECHA}.dump" | head -n 30
```

### Restore
1. Detener los servicios que escriben en la base (`docker compose stop gateway ms-animales ms-adopciones ms-donaciones`).
2. Restaurar con limpieza de objetos previos (`--clean --if-exists`):
```bash
docker compose exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  --clean --if-exists --no-owner < "backups/tpi_negocio_${FECHA}.dump"
```
3. Para restaurar `keycloak-db`, detener antes `keycloak`, restaurar el dump de Keycloak y reiniciar:
```bash
docker compose stop keycloak
docker compose exec -T keycloak-db pg_restore -U "$KC_DB_USER" -d keycloak \
  --clean --if-exists --no-owner < "backups/tpi_keycloak_${FECHA}.dump"
docker compose start keycloak ms-animales ms-adopciones ms-donaciones gateway
```
4. Verificar `GET /actuator/health` en los servicios y confirmar que Flyway no reporta discrepancias (`ddl-auto: validate`).

---

## 7. Rotación de secretos

### 7.1 Contraseñas de base de datos por servicio
Cada microservicio posee su propio usuario PostgreSQL (`animales_user`, `adopciones_user`, `donaciones_user`). Para rotar uno (ejemplo: `adopciones_user`):
```bash
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "ALTER ROLE adopciones_user WITH PASSWORD 'nuevo-secreto-seguro';"
```
Actualizar `ADOPCIONES_DB_PASSWORD` en `.env` (o gestor de secretos) y recrear únicamente ese contenedor:
```bash
docker compose up -d --no-deps --force-recreate ms-adopciones
```

### 7.2 Secreto del cliente `adopciones-service` (`ADOPCIONES_SERVICE_CLIENT_SECRET`)
1. Regenerar el secreto del cliente confidencial `adopciones-service` en la consola de Keycloak (Realm `adopciones` → Clients → `adopciones-service` → Credentials).
2. Actualizar `ADOPCIONES_SERVICE_CLIENT_SECRET` en `.env` / gestor de secretos.
3. Recrear `ms-adopciones` (`docker compose up -d --no-deps --force-recreate ms-adopciones`); `ServiceTokenProvider` hace *prewarm* al arrancar con la nueva credencial.

### 7.3 Credenciales de Mercado Pago (`MP_ACCESS_TOKEN` y `MP_WEBHOOK_SECRET`)
1. Generar el nuevo *Access Token* o secreto de webhook en el panel de Mercado Pago.
2. Actualizar `MP_ACCESS_TOKEN` / `MP_WEBHOOK_SECRET` en el entorno y recrear `ms-donaciones`:
   ```bash
   docker compose up -d --no-deps --force-recreate ms-donaciones
   ```
3. Monitorear `donaciones_webhooks_rechazados_total` y los logs de `ms-donaciones`: si algún webhook llegó durante la ventana de cambio, Mercado Pago reintenta automáticamente ante `401`/`503` y la tarea programada `TareaReconciliacionDonaciones` confirma los pagos pendientes rezagados.

### 7.4 Claves de firma JWT en Keycloak
Keycloak soporta rotación sin downtime (Realm Settings → Keys → Providers): al agregar una nueva clave RS256 con mayor prioridad y pasar la anterior a estado `PASSIVE`, Keycloak sigue publicando la clave anterior en el endpoint JWKS hasta que vencen los tokens ya emitidos. Los validadores `NimbusJwtDecoder` / `NimbusReactiveJwtDecoder` refrescan automáticamente el JWKS al encontrar un `kid` nuevo.
