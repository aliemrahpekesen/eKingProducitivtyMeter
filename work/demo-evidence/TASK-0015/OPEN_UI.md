# Open the EIP demo UI (operator runbook)

No browser/Playwright is available in the CI/agent environment, so **no screenshot was captured**.
Run one of the commands below on your machine and open the printed URL — the demo tenant is
preloaded, so the Session, Engineering Friction, and Connector cards render immediately with clearly
labelled **SIMULATION** data.

## 1. Default (ports 5432 / 8080 / 5173 must be free)

```bash
make demo-down            # stop anything a previous run left
make demo-up              # starts Postgres + backend (demo) + frontend
# → open http://localhost:5173
```

## 2. Overridden ports (use if 5432 / 8080 / 5173 are taken)

`make demo-up` **fails fast** and prints the offending process + the exact override to use, e.g.:

```bash
POSTGRES_PORT=55433 EIP_BACKEND_PORT=18080 VITE_PORT=5174 make demo-up
# → open http://localhost:5174     (frontend; backend on http://localhost:18080)
```

Stop everything with `make demo-down` (stops only what demo-up started — never unrelated containers).

## What you should see

- **Session card** — tenant `00000000-0000-4000-8000-0000000000de`, organization **Demo Org**, status Active.
- **Engineering Friction card** — headline **Platform 74**, worst-first breakdown Platform(74) > Payments(30) > Web(10), with the metric definition + caveats + gaming-risks.
- **Connector list** — 3 connectors (bitbucket / jira / sonarqube), each with a **SIMULATION** badge; a persistent "Demo / simulation data" banner up top.

## Verify from the CLI (backend base URL = the printed one; `:8080` by default)

```bash
T=00000000-0000-4000-8000-0000000000de
curl -H "X-EIP-Tenant: $T" http://localhost:8080/api/v1/session          # → {"tenantId":"…00de","organizationName":"Demo Org"}
curl -H "X-EIP-Tenant: $T" http://localhost:8080/api/v1/connectors        # → 3 connectors, all "simulation": true
curl -H "X-EIP-Tenant: $T" http://localhost:8080/api/v1/friction/summary  # → teams worst-first Platform 74 > Payments 30 > Web 10
curl            http://localhost:8080/api/v1/session                      # → 401 problem+json (no tenant → fails closed, carries traceId)
```

RLS stays enforced: the app connects as `eip_app` (**NOBYPASSRLS**), so a request with no/other tenant
never sees the demo tenant's data.
