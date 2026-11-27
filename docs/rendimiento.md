# Rendimiento — Índices y eliminación de N+1

Este documento describe el procedimiento reproducible para medir el impacto de los índices declarados en `V1__esquema_inicial.sql` contra PostgreSQL 17 real con ~10.000 registros sintéticos por tabla.

## 1. Optimizaciones aplicadas

- **Eliminación de N+1 en `SolicitudService.listar()`:** `@BatchSize(size = 50)` sobre `Solicitud.observaciones` (`@ElementCollection`, carga perezosa). Permite combinar carga en lotes con paginación futura sin incurrir en paginación en memoria (`HHH90003004`). `Solicitud.eventos` no se lee al construir `SolicitudResponse`, por lo que no genera N+1 en el listado.
- **Índices agregados (sin duplicar los índices B-tree generados por las `PRIMARY KEY` y constraints `UNIQUE`):**
  - `adopciones.solicitud`: `idx_solicitud_adoptante_id (adoptante_id)`, `idx_solicitud_animal_estado (animal_id, estado)`, `idx_solicitud_estado_fecha_aprobacion (estado, fecha_aprobacion)`.
  - `donaciones.donacion`: `idx_donacion_donante_id (donante_id)`, `idx_donacion_estado (estado)`.
  - `animales.animal`: `idx_animal_estado_especie (estado, especie)`.

## 2. Precondición: entorno limpio con Flyway

```bash
docker compose down -v
docker compose up --build -d
docker compose ps   # esperar healthy en postgres y los 3 servicios
```

## 3. Siembra de 10.000 registros sintéticos

Ejecutar los siguientes bloques contra el contenedor `tpi-postgres` (con el superusuario `postgres` configurado en `.env`):

### 3.1 `animales.animal` (10.000 filas)

```bash
docker compose exec -T postgres psql -U postgres -d tpi <<'SQL'
INSERT INTO animales.animal (
    nombre, especie, edad_meses, tamanio, necesidades_especiales,
    apto_ninios, apto_otros_animales, requiere_espacio_exterior,
    estado, motivo_no_disponible, fecha_alta, fecha_actualizacion, version
)
SELECT
    'Animal-' || g,
    (ARRAY['PERRO', 'GATO', 'OTRO'])[1 + (g % 3)],
    (g % 120) + 1,
    (ARRAY['CHICO', 'MEDIANO', 'GRANDE'])[1 + (g % 3)],
    NULL,
    (g % 2 = 0),
    (g % 3 = 0),
    (g % 4 = 0),
    (ARRAY['DISPONIBLE', 'RESERVADO', 'EN_ADAPTACION', 'ADOPTADO', 'NO_DISPONIBLE'])[1 + (g % 5)],
    NULL,
    NOW() - (g || ' minutes')::interval,
    NOW() - (g || ' minutes')::interval,
    0
FROM generate_series(1, 10000) AS g;

ANALYZE animales.animal;
SQL
```

### 3.2 `adopciones.solicitud` (10.000 filas)

```bash
docker compose exec -T postgres psql -U postgres -d tpi <<'SQL'
INSERT INTO adopciones.solicitud (
    animal_id, animal_nombre, adoptante_id, estado, fecha_creacion,
    distancia_km, requiere_excepcion, distancia_no_verificada,
    fecha_aprobacion, animal_pendiente, adoptante_activo, version
)
SELECT
    g,
    'Animal-' || g,
    'adoptante-' || (g % 1000),
    CASE
        WHEN g % 20 = 0 THEN 'APROBADA'
        WHEN g % 20 = 1 THEN 'PENDIENTE'
        WHEN g % 3  = 0 THEN 'FINALIZADA'
        ELSE 'RECHAZADA'
    END,
    NOW() - (g || ' minutes')::interval,
    (g % 150) + 0.5,
    (g % 10 = 0),
    false,
    CASE WHEN g % 20 = 0 THEN NOW() - ((g % 30) || ' days')::interval ELSE NULL END,
    CASE WHEN g % 20 = 1 THEN g ELSE NULL END,
    NULL,
    0
FROM generate_series(1, 10000) AS g;

ANALYZE adopciones.solicitud;
SQL
```

### 3.3 `donaciones.donacion` (10.000 filas)

```bash
docker compose exec -T postgres psql -U postgres -d tpi <<'SQL'
INSERT INTO donaciones.donacion (
    referencia, donante_id, monto, moneda, estado,
    fecha_creacion, fecha_actualizacion, link_pago, preferencia_id, pago_id, version
)
SELECT
    'ref-bench-' || g,
    'donante-' || (g % 1000),
    ((g % 50) + 1) * 100.00,
    'ARS',
    CASE WHEN g % 3 = 0 THEN 'CONFIRMADA' ELSE 'PENDIENTE' END,
    NOW() - (g || ' minutes')::interval,
    NOW() - (g || ' minutes')::interval,
    'https://mp.example/pay/' || g,
    'pref-' || g,
    CASE WHEN g % 3 = 0 THEN 'pago-' || g ELSE NULL END,
    0
FROM generate_series(1, 10000) AS g;

ANALYZE donaciones.donacion;
SQL
```

## 4. Comparación `EXPLAIN (ANALYZE, BUFFERS)` antes y después de los índices

Para medir **sin índices** sin alterar el esquema de forma permanente, envolver cada `DROP INDEX` en una transacción que finaliza con `ROLLBACK`:

```sql
-- Ejemplo: comparar TareaVencimientos (R-09) SIN índice vs CON índice

-- 1) CON índice (estado actual tras Flyway V1):
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM adopciones.solicitud
WHERE estado = 'APROBADA' AND fecha_aprobacion < NOW() - INTERVAL '7 days';

-- 2) SIN índice (se revierte al hacer ROLLBACK):
BEGIN;
DROP INDEX adopciones.idx_solicitud_estado_fecha_aprobacion;
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM adopciones.solicitud
WHERE estado = 'APROBADA' AND fecha_aprobacion < NOW() - INTERVAL '7 days';
ROLLBACK;
```

Repetir el mismo patrón (`EXPLAIN (ANALYZE, BUFFERS)` con índice y dentro de `BEGIN; DROP INDEX ...; EXPLAIN ...; ROLLBACK;`) para el resto de consultas:

```sql
-- Solicitudes por adoptante (SolicitudService.listar)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM adopciones.solicitud
WHERE adoptante_id = 'adoptante-500' ORDER BY id DESC;

-- Solicitud pendiente por animal (SolicitudService.crear, R-01)
EXPLAIN (ANALYZE, BUFFERS)
SELECT 1 FROM adopciones.solicitud
WHERE animal_id = 4001 AND estado = 'PENDIENTE' LIMIT 1;

-- Catálogo de animales por estado y especie (AnimalService.listar)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM animales.animal
WHERE estado = 'DISPONIBLE' AND especie = 'PERRO' ORDER BY id ASC;

-- Donaciones por donante (DonacionService.listar)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM donaciones.donacion
WHERE donante_id = 'donante-500' ORDER BY id DESC;

-- Total confirmado de donaciones (DonacionService.totalConfirmado)
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(SUM(monto), 0) FROM donaciones.donacion
WHERE estado = 'CONFIRMADA';
```

## 5. Resultados medidos

<!-- TODO: completar esta tabla con los planes y tiempos reales de EXPLAIN (ANALYZE, BUFFERS) obtenidos sobre PostgreSQL 17 -->

| Consulta | Índice evaluado | Plan sin índice | Tiempo / Buffers sin índice | Plan con índice | Tiempo / Buffers con índice |
|---|---|---|---|---|---|
| `TareaVencimientos` (`estado = 'APROBADA' AND fecha_aprobacion < ...`) | `idx_solicitud_estado_fecha_aprobacion` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` |
| `SolicitudService.listar` (`adoptante_id = ?`) | `idx_solicitud_adoptante_id` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` |
| `SolicitudService.crear` (`animal_id = ? AND estado = 'PENDIENTE'`) | `idx_solicitud_animal_estado` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` |
| `AnimalService.listar` (`estado = 'DISPONIBLE' AND especie = 'PERRO'`) | `idx_animal_estado_especie` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` |
| `DonacionService.listar` (`donante_id = ?`) | `idx_donacion_donante_id` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` |
| `DonacionService.totalConfirmado` (`estado = 'CONFIRMADA'`) | `idx_donacion_estado` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` | `<!-- TODO -->` |
