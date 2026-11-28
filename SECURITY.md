# Política de Seguridad

## Versiones soportadas

| Versión | Soporte de seguridad |
|---|---|
| `main` (en desarrollo) | Activo |

## Reporte de vulnerabilidades

Si detectás una vulnerabilidad de seguridad en este repositorio:

1. **No abras un issue público** con detalles explotables ni credenciales.
2. Utilizá **GitHub Security Advisories** (*Security → Advisories → Report a vulnerability*) en este repositorio o contactá al mantenedor de forma privada.
3. Incluí en el reporte:
   - Componente afectado (`gateway`, `ms-animales`, `ms-adopciones`, `ms-donaciones`, `common` o `infra/`).
   - Pasos mínimos para reproducir el problema y el impacto esperado.
   - Si aplica, una sugerencia de mitigación.

## Alcance y exclusiones

- **En alcance:** fallas de autenticación/autorización JWT, escalamiento de privilegios (RBAC), acceso a recursos de terceros (IDOR), bypass de firma HMAC en webhooks de Mercado Pago, fuga de PII/secretos en logs o respuestas, e inyecciones.
- **Fuera de alcance:** credenciales ficticias de demostración documentadas en `.env.example` o el uso de `start-dev` y *password grant* en el perfil local de desarrollo (documentado explícitamente en [docs/SEGURIDAD.md](docs/SEGURIDAD.md)).
