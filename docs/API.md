# Referencia de la API

Todas las rutas públicas se consumen por el Gateway (`http://localhost:8080`) con
`Authorization: Bearer <JWT>`. Formato de error único:

```json
{"timestamp":"2026-10-27T10:15:30","status":409,"error":"Conflicto",
 "message":"El animal 7 no esta disponible (estado RESERVADO) (R-01)","path":"/api/solicitudes","errores":[]}
```
`errores` lista `{campo, mensaje}` solo en los 400 de validación. Códigos: 200/201 (con `Location`),
400 datos inválidos, 401 sin token o token/firma inválida, 403 rol insuficiente o recurso ajeno, 404,
409 regla o estado, 413 cuerpo superior al límite del Gateway (`GATEWAY_MAX_BODY_BYTES`, 1 MiB por defecto),
503 otro servicio o proveedor no responde (504 si el Gateway agota la espera).

El Gateway agrega cabeceras de seguridad (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, `Cache-Control: no-store`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`)
y habilita CORS únicamente para los orígenes definidos en `GATEWAY_CORS_ALLOWED_ORIGINS` (bloqueado por defecto).

La especificación OpenAPI de cada servicio está en `/v3/api-docs` y `/swagger-ui.html` de su puerto.

Los listados (`GET /api/animales`, `GET /api/solicitudes` y `GET /api/donaciones`) aceptan parámetros
opcionales de paginación `page` (por defecto `0`, `>= 0`) y `size` (por defecto `50`, entre `1` y
`100`), mantienen un arreglo JSON como cuerpo y devuelven el total de registros en el encabezado
`X-Total-Count`. Valores fuera de rango responden `400` con `ErrorResponse`.

## ms-animales

| Método y ruta | Rol | Cuerpo | Respuestas |
|---|---|---|---|
| `POST /api/animales` | COORDINADOR | `AnimalRequest` | 201 `AnimalResponse`, 400 |
| `GET /api/animales?estado=&especie=&page=0&size=50` | ADOPTANTE, COORDINADOR | — | 200 lista + `X-Total-Count`, 400 si el filtro o la paginación son inválidos |
| `GET /api/animales/{id}` | ADOPTANTE, COORDINADOR | — | 200, 404 |
| `PUT /api/animales/{id}` | COORDINADOR | `AnimalRequest` | 200, 400, 404 (no cambia el estado) |
| `POST /api/animales/{id}/marcar-no-disponible` | COORDINADOR | `{"motivo"}` | 200, 400, 404, 409 (R-13 o no DISPONIBLE) |
| `POST /api/animales/{id}/marcar-disponible` | COORDINADOR | — | 200, 404, 409 |
| `GET /internal/animales/{id}` | SERVICIO (no publicada) | — | 200, 404 |
| `PUT /internal/animales/{id}/estado` | SERVICIO (no publicada) | `{"nuevoEstado","solicitudId"}` (`solicitudId` opcional, idempotente) | 200, 400, 404, 409 transición inválida |

`AnimalRequest`: `nombre` (obligatorio, ≤100), `especie` (`PERRO|GATO|OTRO`), `edadMeses` (0–400),
`tamanio` (`PEQUENIO|MEDIANO|GRANDE`), `necesidadesEspeciales` (opcional), `aptoNinios`,
`aptoOtrosAnimales`, `requiereEspacioExterior` (booleanos obligatorios).
`AnimalResponse`: lo anterior + `id`, `estado`, `motivoNoDisponible`.

## ms-adopciones

| Método y ruta | Rol | Cuerpo | Respuestas |
|---|---|---|---|
| `PUT /api/adoptantes/me/perfil` | ADOPTANTE | `PerfilRequest` | 200, 400 |
| `GET /api/adoptantes/me/perfil` | ADOPTANTE | — | 200, 404 |
| `GET /api/adoptantes/{adoptanteId}/perfil` | COORDINADOR | — | 200, 404 |
| `POST /api/solicitudes` | ADOPTANTE | `{"animalId"}` | 201 PENDIENTE o RECHAZADA (R-04); 404 animal; 409 R-01/R-02/R-03; 503 Animales |
| `GET /api/solicitudes?estado=&controlVencido=&page=0&size=50` | ADOPTANTE (propias), COORDINADOR (todas; `controlVencido=true` solo COORDINADOR) | — | 200 lista + `X-Total-Count`, 400, 403 |
| `GET /api/solicitudes/reportes` | COORDINADOR | — | 200 resumen por estado, controles vencidos y tiempo promedio de resolución |
| `POST /api/solicitudes/reconciliacion` | COORDINADOR | — | 200 resultado de reconciliación con `ms-animales` (ADR-06) |
| `GET /api/solicitudes/{id}` | dueño o COORDINADOR | — | 200, 403, 404 |
| `GET /api/solicitudes/{id}/historial` | dueño o COORDINADOR | — | 200 lista de eventos |
| `POST /api/solicitudes/{id}/cancelar` | ADOPTANTE dueño | — | 200, 403, 409 si no PENDIENTE |
| `POST /api/solicitudes/{id}/aprobar` | COORDINADOR | `{"justificacion"}` opcional | 200, 409 (estado, R-08, animal no reservable), 503 |
| `POST /api/solicitudes/{id}/rechazar` | COORDINADOR | `{"motivo"}` | 200, 400 sin motivo, 409 |
| `POST /api/solicitudes/{id}/confirmar-entrega` | COORDINADOR | — | 200, 409 (no APROBADA o plazo vencido: se cancela), 503 |
| `POST /api/solicitudes/{id}/cancelar-entrega` | COORDINADOR | `{"motivo"}` | 200, 400, 409, 503 |
| `POST /api/solicitudes/{id}/control-final` | COORDINADOR | `{"resultado":"CONFORME|DESFAVORABLE","observaciones"}` | 200, 400 (desfavorable sin observaciones), 409 (R-11), 503 |
| `POST /api/solicitudes/{id}/devolucion` | ADOPTANTE dueño | `{"motivo"}` | 200, 403, 409 si no EN_ADAPTACION |

`PerfilRequest`: `nombreCompleto`, `telefono`, `provincia`, `localidad`, `direccion` (calle y altura),
`tipoVivienda` (`CASA|DEPARTAMENTO`), `tienePatio`, `hayNinios`, `hayOtrosAnimales` — todos obligatorios.

`SolicitudResponse`: `id`, `animalId`, `animalNombre`, `adoptanteId`, `estado`, `fechaCreacion`,
`distanciaKm`, `requiereExcepcion`, `distanciaNoVerificada`, `observaciones[]`, `motivoRechazo`,
`justificacionExcepcion`, `fechaAprobacion`, `fechaEntrega`, `fechaControl`, `resultadoControl`,
`observacionesControl`, `motivoCierre`.

Ejemplo real (verificación local del 27/10/2026, Georef en vivo):
```json
{"estado":"PENDIENTE","distanciaKm":2.7,"requiereExcepcion":false,"distanciaNoVerificada":false,
 "observaciones":["El animal necesita espacio exterior y el hogar no tiene patio (R-05)"]}
```

## ms-donaciones

| Método y ruta | Rol | Cuerpo | Respuestas |
|---|---|---|---|
| `POST /api/donaciones` | ADOPTANTE | `{"monto": 500}` (header opcional `Idempotency-Key`, máx. 128 chars) | 201 con `linkPago`, 400 (D-01 o validación), 409 (`Idempotency-Key` con distinto monto, ADR-18), 503 Mercado Pago |
| `GET /api/donaciones?page=0&size=50` | ADOPTANTE (propias), COORDINADOR (todas) | — | 200 lista + `X-Total-Count`, 400 |
| `GET /api/donaciones/{id}` | dueño o COORDINADOR | — | 200 (si PENDIENTE consulta el pago), 403, 404 |
| `GET /api/donaciones/total` | COORDINADOR | — | 200 `{"totalConfirmado","cantidad"}` |
| `POST /api/donaciones/webhooks/mercadopago` | Público (HMAC-SHA256 `x-signature`) | Notificación de pago Mercado Pago (`type`, `data.id`) | 200 procesado idempotente, 401 firma inválida/ausente, 503 secreto no configurado o MP caído |

`DonacionResponse`: `id`, `donanteId`, `monto`, `moneda` (ARS), `estado` (`PENDIENTE|CONFIRMADA`),
`fechaCreacion`, `fechaActualizacion`, `linkPago`, `verificado`.

### Pruebas locales del webhook de Mercado Pago (túnel HTTPS)

Mercado Pago requiere una URL HTTPS pública para enviar notificaciones reales. En desarrollo local:
1. Configurar `MP_WEBHOOK_SECRET` en `.env` con la clave secreta del panel de Webhooks de Mercado Pago (en `docker-compose.demo.yml` se fija `demo-webhook-secret`).
2. Exponer el Gateway (`localhost:8080`) con un túnel temporal, por ejemplo `ngrok http 8080` o `cloudflared tunnel --url http://localhost:8080`.
3. Registrar `<URL-publica>/api/donaciones/webhooks/mercadopago` para el tópico **Pagos** (`payment`). Cada notificación valida `x-signature` con HMAC-SHA256 y consulta `GET /v1/payments/{id}` antes de confirmar la donación.

## Integraciones externas

| Proveedor | Llamada | Uso | Falla |
|---|---|---|---|
| Georef | `GET https://apis.datos.gob.ar/georef/api/v2.0/direcciones?direccion&provincia&localidad&max=1` | `direcciones[0].ubicacion.lat/lon` → distancia al refugio (R-06) | timeout 4 s, error, sin resultados o formato inesperado → `distanciaNoVerificada` |
| Mercado Pago | `POST https://api.mercadopago.com/checkout/preferences` | `id`, `init_point` → `linkPago` | 503 y no se guarda la donación |
| Mercado Pago | `GET https://api.mercadopago.com/v1/payments/search?external_reference=` | `results[].status == "approved"` → CONFIRMADA (D-02) | queda PENDIENTE con `verificado=false` |
| Mercado Pago | `GET https://api.mercadopago.com/v1/payments/{id}` | Verificación autoritativa del pago al recibir un webhook firmado (F2) | 503 para que Mercado Pago reintente |
| Keycloak | `POST /realms/adopciones/protocol/openid-connect/token` (client credentials) | token SERVICIO | 503 |

## Especificaciones OpenAPI exportadas

Las especificaciones OpenAPI 3 de cada servicio se versionan y verifican en el build (`./mvnw -B test -Pexport-openapi -Dtest=OpenApiExportTest`):
- [docs/openapi/ms-animales.json](openapi/ms-animales.json)
- [docs/openapi/ms-adopciones.json](openapi/ms-adopciones.json)
- [docs/openapi/ms-donaciones.json](openapi/ms-donaciones.json)
