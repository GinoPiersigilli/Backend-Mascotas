# Guía de Contribución

Gracias por tu interés en contribuir a este repositorio. Todo cambio debe preservar los contratos públicos documentados en [`docs/CONTRATOS.md`](docs/CONTRATOS.md) y superar las compuertas automatizadas de calidad y seguridad.

---

## 1. Requisitos previos

- **JDK 21** (exacto: `maven-enforcer-plugin` exige `[21,22)`).
- **Docker + Docker Compose v2** (solo requerido para `-Pintegration-tests` con Testcontainers y para el stack E2E).
- No hace falta instalar Maven globalmente: usar siempre `./mvnw` (Linux/macOS/Git Bash) o `.\mvnw.cmd` (PowerShell).

---

## 2. Comandos habituales de desarrollo

```bash
# 1) Tests rápidos de unidad y reglas ArchUnit (~1 min)
./mvnw -B test -Dgroups=unit

# 2) Verificación completa del reactor (Enforcer + 237 tests + Checkstyle + SpotBugs + JaCoCo)
./mvnw -B clean verify

# 3) Integración contra PostgreSQL 17 real (Flyway V1..V3 + ddl-auto: validate)
./mvnw -B verify -Pintegration-tests

# 4) Regenerar especificaciones OpenAPI versionadas en docs/openapi/*.json
./mvnw -B test -Pexport-openapi -Dtest=OpenApiExportTest

# 5) Prueba de humo E2E completa sin credenciales externas (8 contenedores + WireMock)
cp -n .env.example .env
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build -d --wait
EXPECT_DONACIONES=true bash ./scripts/prueba-humo.sh
```

---

## 3. Reglas de arquitectura y estilo verificadas automáticamente

El build falla en la fase `verify` si se incumple cualquiera de estas reglas:
1. **Sin Lombok ni `@Value` disperso:** usar `record` de Java 21 para DTOs y `@ConfigurationProperties` con `@Validated` para configuración (`checkstyle.xml` + `ArquitecturaTest`).
2. **Separación estricta de capas (`ArchUnit`):**
   - Los controladores no acceden directamente a repositorios.
   - El paquete `..model..` no depende de controladores, servicios, clientes HTTP ni DTOs.
   - Cero dependencias cruzadas entre microservicios; `gateway` no depende de `common`.
3. **Calidad estática:** `0` violaciones de Checkstyle (`config/checkstyle/checkstyle.xml`) y `0` bugs en SpotBugs (`effort=Max`, `threshold=Medium`).
4. **Cobertura mínima (JaCoCo):** `>= 80%` de líneas en cada paquete `*.model` y `*.service`, y `>= 85%` de líneas sobre el bundle aislado `common`.
5. **Evolución de esquemas y contratos:** cualquier cambio en base de datos requiere una nueva migración `V<N>__descripcion.sql` (nunca editar migraciones ya publicadas) y mantener retrocompatibilidad aditiva bajo `/api/**` ([ADR-19](docs/ADR.md#adr-19-política-de-versionado-de-la-api-rest-api)).

---

## 4. Flujo de ramas, commits y Pull Requests

- Crear una rama descriptiva desde `main` actualizado (`feat/...`, `fix/...`, `docs/...`, `test/...`, `chore/...`).
- Usar **Conventional Commits** claros y atómicos (ej. `feat(donaciones): ...`, `fix(gateway): ...`).
- Nunca versionar archivos `.env`, credenciales reales ni PII en logs o fixtures.
- Si el cambio introduce una decisión arquitectónica relevante, registrarla en [`docs/ADR.md`](docs/ADR.md) y actualizar [`docs/openapi/*.json`](docs/openapi) si cambian endpoints o esquemas HTTP.
