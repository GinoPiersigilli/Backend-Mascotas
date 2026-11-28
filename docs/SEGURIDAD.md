# Modelo de Amenazas y Controles de Seguridad

Este documento resume la postura de seguridad del backend, las fronteras de confianza (*trust boundaries*), los controles implementados y el checklist necesario antes de un despliegue productivo.

---

## 1. Fronteras de confianza y superficie expuesta

| Frontera | Exposición | Controles |
|---|---|---|
| **Internet / Cliente → API Gateway (`:8080`)** | Única entrada pública a `/api/**` | Validación JWT (firma RS256, `iss`, `exp`/`nbf` con *clock skew* 30 s, `azp` en clientes públicos permitidos, `aud` opcional), CORS cerrado por defecto (`GATEWAY_CORS_ALLOWED_ORIGINS`), cabeceras de seguridad (`nosniff`, `DENY`, `no-referrer`, `no-store`, CSP `default-src 'none'`), límite de cuerpo (`GATEWAY_MAX_BODY_BYTES`, 1 MiB → `413`), `/internal/**` y `/actuator/prometheus` sin ruta y denegados. |
| **Mercado Pago → Webhook (`POST /api/donaciones/webhooks/mercadopago`)** | Ruta pública explícita sin JWT | Firma HMAC-SHA256 (`x-signature` validado en tiempo constante con `MP_WEBHOOK_SECRET`, rechazado por defecto si el secreto no está configurado) + consulta autoritativa `GET /v1/payments/{id}` antes de confirmar la donación. |
| **Red interna `refugio-net` → Microservicios (`:8081–:8083`)** | No publican puertos en `docker-compose.yml` | Defensa en profundidad (ADR-04): cada servicio revalida el JWT independientemente del Gateway, aplica RBAC fino y verifica pertenencia del recurso (IDOR → `403`). `/internal/animales/**` y `/actuator/prometheus` exigen rol `SERVICIO`. Swagger deshabilitado por defecto (`SWAGGER_ENABLED=false`). |
| **Microservicios → PostgreSQL (`:5432`)** | Red interna `refugio-net` | Un esquema y un usuario propietario por servicio (`animales_user`, `adopciones_user`, `donaciones_user`); sin permisos cruzados (`permission denied` verificado). Flyway + `ddl-auto: validate`. |
| **Contenedores** | Runtime Docker | Imágenes multi-stage `eclipse-temurin:21-jre` ejecutadas con usuario no root (`USER app`). |

---

## 2. Validación JWT: emisor, cliente (`azp`), audiencia (`aud`) y *clock skew*

Implementado en `JwtTokenValidators` (`common`) y `GatewayJwtValidators` (`gateway`):

- **Firma y descarga de claves:** `jwk-set-uri` interno (`http://keycloak:8080/.../certs`) sin *discovery* bloqueante al arranque (ADR-05).
- **Emisor (`iss`):** coincidencia exacta contra `KEYCLOAK_ISSUER_URI` (`http://localhost:8180/realms/adopciones` en local).
- ***Clock skew* explícito:** `JWT_CLOCK_SKEW` fijado en **`30s`** por defecto (más estricto que los `60s` implícitos de Spring Security).
- **Cliente autorizado (`azp`):**
  - El Gateway restringe `GATEWAY_JWT_ALLOWED_CLIENTS` a clientes públicos de usuario (`adopciones-cli,adopciones-pkce`), rechazando tokens `adopciones-service` antes incluso de evaluar roles.
  - Los microservicios restringen `JWT_ALLOWED_CLIENTS` a `adopciones-cli,adopciones-pkce,adopciones-service`.
- **Audiencia (`aud`):** soportada mediante `JWT_REQUIRED_AUDIENCE` (cuando se configura un *Audience Protocol Mapper* en el realm productivo, por ejemplo `adopciones-api`).

---

## 3. Modelo de amenazas resumido (STRIDE)

| Categoría | Amenaza principal | Control implementado y evidencia |
|---|---|---|
| **Spoofing** | Uso de tokens falsos, vencidos o emitidos para otro cliente; suplantación de webhooks de Mercado Pago | Firma RS256 + `iss` + `azp` + `clockSkew` (`JwtTokenValidatorsTest`, `GatewayJwtValidatorTest`); HMAC-SHA256 + re-verificación contra API MP (`DonacionesReconciliacionYWebhookTest`). |
| **Tampering** | Modificar estado de un animal desde fuera del flujo; duplicar donaciones con el mismo pago o reintentos | `/internal/**` bloqueado en Gateway y protegido con `ROLE_SERVICIO`; `@Version` optimista (`SolicitudConcurrenciaTest`); `uk_donacion_pago_id` + `Idempotency-Key` con hash SHA-256 (`DonacionIdempotenciaTest`). |
| **Repudiation** | Decisiones de adopción sin trazabilidad | `EventoSolicitud` inmutable en `evento_solicitud` con `fecha`, `actor` (`sub` o `sistema`) y `detalle`; logs estructurados con `X-Correlation-ID`. |
| **Information Disclosure** | IDOR sobre solicitudes/historiales/donaciones ajenas; detalles de infraestructura en Actuator; PII en logs | Validación de pertenencia en servicio (`AdopcionesSecurityMatrixTest`, `DonacionesSecurityMatrixTest`); `management.endpoint.health.show-details: never` y probes `liveness`/`readiness`; sin domicilios, teléfonos ni tokens en logs (solo IDs técnicos y `sub`). |
| **Denial of Service** | Cuerpos HTTP enormes; caída de dependencias externas agotando hilos | `MaxBodySizeFilter` + `spring.http.codecs.max-in-memory-size` (`413` JSON, `GatewayHardeningTest`); timeouts diferenciados + Resilience4j CircuitBreaker/Retry solo en GET (`ClientesResilienciaTest`, `MercadoPagoResilienciaTest`). |
| **Elevation of Privilege** | `ADOPTANTE` invocando rutas de `COORDINADOR` o `SERVICIO`; `COORDINADOR` alterando estados internos | Matriz RBAC por método y ruta en Gateway y en cada servicio (`*SecurityMatrixTest`, `AnimalControllerSecurityTest`, `GatewaySecurityTest`). |

---

## 4. Auditoría de PII y secretos en logs

- **Tokens y credenciales:** `ServiceTokenProvider`, `MercadoPagoClient` y `VerificadorFirmaMercadoPago` nunca registran `client_secret`, `MP_ACCESS_TOKEN`, `MP_WEBHOOK_SECRET` ni `Authorization`.
- **Datos personales (PII):** `PerfilService`, `SolicitudService`, `GeorefClient` y `EventosAdopcionListener` omiten domicilio, teléfono y nombre completo en logs (solo registran IDs de solicitud/animal, distancia o motivo de error técnico).
- **Escaneo automatizado y gestión de CVEs:** `.github/workflows/security.yml` ejecuta `gitleaks` sobre todo el historial de git, `CodeQL` sobre Java, `Trivy` sobre filesystem/IaC y sobre las 4 imágenes Docker, y `dependency-review` en cada PR. Las dependencias transitivas con parches en la serie 3.5 (`pgjdbc 42.7.12`, `jackson 2.21.9`, `tomcat 10.1.62`, `netty 4.1.141.Final`, `bouncycastle 1.87`) se fijan en el `pom.xml` raíz; las 3 CVEs de Spring Framework `6.2.19` sin parche OSS en la rama 6.2 (`CVE-2026-47884` XsltView, `CVE-2026-47890` SSE con vistas y `CVE-2026-47892` WebFlux Functional Endpoints) están documentadas en `.trivyignore` con justificación VEX (`not_affected` sobre esta arquitectura REST sin vistas ni functional endpoints).

---

## 5. Checklist obligatorio antes de producción (Keycloak y entorno)

El entorno local de `docker-compose.yml` prioriza reproducibilidad sin certificados ni dependencias externas. Antes de exponer a Internet:

1. **Keycloak en modo productivo:** reemplazar `start-dev` por `start --optimized`, terminar TLS con certificado real (`sslRequired: external` o `all`) y fijar `KC_HOSTNAME` al dominio HTTPS real.
2. **Deshabilitar *password grant*:** eliminar/deshabilitar `adopciones-cli` y usar exclusivamente **`adopciones-pkce`** (*Authorization Code + PKCE S256*, ya incluido en `infra/keycloak/realm-adopciones.json`).
3. **Activar audiencia explícita:** añadir un *Audience Protocol Mapper* (`adopciones-api`) en Keycloak y definir `JWT_REQUIRED_AUDIENCE=adopciones-api` en Gateway y microservicios.
4. **Gestión de secretos:** inyectar credenciales de BD, Keycloak y Mercado Pago desde un gestor de secretos (Vault / AWS Secrets Manager / Kubernetes Secrets), rotar `MP_WEBHOOK_SECRET` y deshabilitar `ANIMALES_DATOS_DEMO=false`.
5. **Rate limiting en el borde:** configurar límites de tasa por IP/usuario en el ingress/reverse proxy delante del Gateway (especialmente sobre `/api/donaciones/webhooks/mercadopago` y Keycloak).
