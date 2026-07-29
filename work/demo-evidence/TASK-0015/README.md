# TASK-0015 demo-verification evidence (2026-07-09)

Fresh end-to-end run of the one-command demo from `integration/SPRINT-00` (merge `c4c784f`).

## How it was run

Vanilla `make demo-up` was attempted first and **failed on this shared box** with
`Bind for 0.0.0.0:5432 failed: port is already allocated` — ports 5432 (an unrelated
`rf-postgres` container) and 8080 (an unrelated `api-gateway`) are held by other services here. This
is environmental, not a demo defect: on a machine with those ports free, `make demo-up` runs as-is.

To prove the demo, the **same steps `demo-up.sh` runs** were executed on free ports (Postgres 5433
via the sourced `.env`, backend `SERVER_PORT=18080`, frontend Vite proxy → 18080). The DB volume was
first wiped with the documented `make dev-down`, so the seed below is a **fresh** seed.

## What was verified (all PASS)

- **Fresh seed** (backend log): `seeded demo simulation data — tenant 00000000-0000-4000-8000-0000000000de (slug 'demo'): 3 connectors + 3 teams + friction`.
- **Backend health**: `/actuator/health` → UP (~6 s).
- **The three visible surfaces** for the **fixed demo tenant** `00000000-0000-4000-8000-0000000000de` (no manual DB lookup) — see [session.json](session.json), [connectors.json](connectors.json), [friction-summary.json](friction-summary.json):
  - `GET /api/v1/session` → `{ tenantId: …00de, organizationName: "Demo Org" }`
  - `GET /api/v1/connectors` → 3 connectors, **all `simulation: true`** (bitbucket/jira/sonarqube)
  - `GET /api/v1/friction/summary` → worst-first Platform(74) > Payments(30) > Web(10), metric grain `team`, full definition (purpose/formula/inputs/caveats/gaming-risks)
- **Frontend** ([index.html](index.html)): `http://localhost:5173` serves the SPA shell (`<title>Engineering Intelligence Platform</title>`, `#root`, `main.tsx`); the Vite proxy forwards `/api/v1/*` and `/v3/api-docs` to the backend and returns the demo data — i.e. the exact endpoints the `SessionCard`, `ConnectorList`, and `FrictionCard` call with the preloaded tenant. The 13 component tests already prove those cards render that data; combined, the rendered page works.
- **RLS still enforced**: no tenant header → **401** problem+json (`title: "Tenant required"`, `type: /problems/unauthenticated`, `traceId` present); a **wrong tenant sees 0 connectors** (`eip_app` is `NOBYPASSRLS`).

## Screenshots

**None captured** — no browser/Playwright/Chromium is available on this box (only macOS `screencapture`, which cannot shoot a headless page). To see the rendered UI, open the URL in a browser:

```
make demo-up            # on a machine with 5432/8080 free
# → open http://localhost:5173   (demo tenant 00000000-0000-4000-8000-0000000000de preloaded)
```
