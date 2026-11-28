## Resumen
<!-- Qué problema resuelve este cambio y por qué -->

## Cambios principales
- 

## Verificación ejecutada
- [ ] `./mvnw -B clean verify` en verde (Enforcer, Checkstyle, SpotBugs, ArchUnit y JaCoCo gates)
- [ ] `./mvnw -B verify -Pintegration-tests` en verde (si se modificaron entidades, repositorios o migraciones Flyway)
- [ ] `docs/openapi/*.json` actualizado (si cambiaron endpoints, parámetros o DTOs públicos)
- [ ] Sin ruptura de contratos existentes (`docs/CONTRATOS.md`) ni credenciales/PII en código o logs
