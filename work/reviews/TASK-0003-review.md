# Code Review — TASK-0003 Docker Compose dev stack

- **Reviewer:** R-CR (independent, fresh-context) · **Gate:** G8 · **Date:** 2026-07-07
- **Task:** [../tasks/TASK-0003.md](../tasks/TASK-0003.md) · story P0-E1-S3 · change class CC-7
- **Branch:** `feature/TASK-0003-compose-dev-stack` · **Base:** `integration/SPRINT-00` · **Commit:** `7b1fa86`
- **Checklists applied:** [CodeReviewChecklist.md](../../engineering-operating-system/CodeReviewChecklist.md) · [Sprint00ReviewChecklist.md](../../sprints/sprint-00/Sprint00ReviewChecklist.md) · conformance cross-check vs [DockerCompose.md §1–4/§10/§13](../../docs/infrastructure/DockerCompose.md)

## Overall verdict (re-review, fix commit `99f7912`): **APPROVED**

> **Round 1 (commit `7b1fa86`): MAJOR — changes requested.** One MAJOR (`make dev-up` non-zero on fresh boot) + 3 MINOR + 4 NIT. Original findings preserved below.
> **Round 2 (commit `99f7912`): APPROVED.** MAJOR-1 cleared, MINOR-1/2 fixed, MINOR-3 → DEBT-005 — all independently re-verified from a clean state (§ Re-review verification). Zero open BLOCKERs, zero unwaived MAJORs → the approval rule is met. May merge into `integration/SPRINT-00`.

---

## Re-review verification (2026-07-07, commit `99f7912`)

Per [CodeReviewChecklist §2 step 7](../../engineering-operating-system/CodeReviewChecklist.md), only the fixes and new commits were verified; settled NITs were not reopened. The fix diff (`7b1fa86..99f7912`) is precisely scoped to the findings — no scope creep.

| Finding | Fix in the diff | Reviewer re-verification | Status |
|---|---|---|---|
| **MAJOR-1** (`make dev-up` non-zero on fresh boot) | Makefile: `up -d --wait $(DEV_SERVICES)` (8 long-running services) then `run --rm minio-init` — the one-shot is out of the `--wait` set | Clean `make dev-down && make dev-up` → **EXIT 0** at 41 s; 8 services healthy; both buckets created via `run --rm` | ✅ CLEARED |
| **MINOR-1** (image tags vs §2) | kafka `3.7.0`, minio `2024-06-13`, otel `0.102.0`, prometheus `v2.53.0`, grafana `11.1.0` | `docker … ps` shows the §2-aligned tags; all boot **healthy** (no regression from the downgrades) | ✅ FIXED |
| **MINOR-2** (pgvector init SQL) | new `postgres/00-extensions.sql` (`CREATE EXTENSION IF NOT EXISTS vector`) mounted to `docker-entrypoint-initdb.d` | `select extname from pg_extension where extname='vector'` → **`vector`** | ✅ FIXED |
| **MINOR-3** (`.env` CHANGE_ME) | filed as **DEBT-005** (owner R-DOA/R-SA; target = the app-container task) | Present in `work/debt-register.md` | ✅ RECORDED |

**Independent validation (clean state):**

| Command / check | Result |
|---|---|
| `docker compose config -q` | VALID |
| `make dev-down` (clean start) | exit 0 |
| **`make dev-up` (fresh, no pipe)** | **EXIT 0**, 41 s |
| Long-running services | postgres/redis/kafka/minio/keycloak/prometheus/grafana **healthy**; otel-collector running (distroless) |
| MinIO buckets | `eip-artifacts` + `eip-ingest` present |
| pgvector | `vector` extension enabled |
| Keycloak OIDC discovery | **200**, issuer `…/auth/realms/eip` |
| `make dev-urls` | prints endpoints + dev creds |
| `make dev-down` | exit 0, containers gone |
| `.env` staged / secrets | `.env` git-ignored, **0 tracked `.env` files**; only documented dev placeholders (DEBT-005) — no real secrets |

**Acceptance criteria (post-fix):** AC-1 ✅ (healthy < 2 min **and `make dev-up` exits 0**) · AC-2 ✅ · AC-3 ✅ · AC-4 ✅ — all now pass.

**No new regression:** the fix diff only realigns image tags (all boot healthy), adds one init-SQL mount (pgvector verified), and reworks `dev-up`'s wait strategy (exits 0). Nothing else touched.

**Remaining findings:** none blocking. The round-1 NITs (compose filename vs §1's `compose.yaml`, volume naming, Keycloak internal port, commit-message `[TASK-NNNN]` trailer) stand as author's-discretion items — NIT-4 to be honored in the squash-merge message; not reopened.

**Merge decision:** **APPROVED for merge** into `integration/SPRINT-00`. Once merged and the TASK-0002 CI runs on push, the task completes MERGED → VERIFIED → DONE.

---

## Round-1 findings (commit `7b1fa86`) — preserved for the record

## Overall verdict: **MAJOR — changes requested**

One MAJOR defect on the task's primary happy path (`make dev-up` returns a non-zero exit on a fresh boot). The stack itself comes up healthy and every other criterion passes — all independently re-run by the reviewer — but the approval rule ([CodeReviewChecklist §4](../../engineering-operating-system/CodeReviewChecklist.md): "zero open BLOCKERs and zero unwaived MAJORs") is not met, so **this cannot be merged until MAJOR-1 is fixed or explicitly waived** by the owning role (R-DOA) with a recorded justification + DEBT entry. Plus 3 MINOR (all from the deferred DockerCompose.md conformance cross-check) and 4 NIT.

## Findings by severity

### BLOCKER — none

### MAJOR

- **[MAJOR-1] `make dev-up` returns `Error 1` on a fresh boot.** `make dev-up` runs `docker compose up -d --wait`, and the wait set includes the one-shot `minio-init` (`restart: "no"`). On a fresh boot `--wait` monitors `minio-init`, which **exits mid-wait**, and compose reports the exited container as a failure → the target exits non-zero **even though the stack came up healthy**. Reproduced by the reviewer: first `make dev-up` on a clean state → **Error 1 at 28 s**; an immediate second `up --wait` (stack already up, init already exited) → **exit 0**. So the one-command boot — the task's core deliverable, and exactly the AC-1 / clean-clone-demo path — is **flaky-failing on first run**. This breaks `make dev-up && <next>` chains, CI usage, and the demo. *Fix (before merge, or waive with justification + DEBT):* keep the one-shot out of the `--wait` set — e.g. `up -d --wait <long-running services>` then run `minio-init` separately (`up minio-init` / `run --rm minio-init`), or replace `--wait` with a health-poll loop that ignores completed one-shots. Re-verify that a fresh `make dev-up` exits 0.

### MINOR (DockerCompose.md §2 conformance — the cross-check the author deferred)

- **[MINOR-1] Image tags drift from the [DockerCompose.md §2](../../docs/infrastructure/DockerCompose.md) catalog.** All images are pinned (good, per §1.1), but to different tags than §2 specifies: kafka `3.8.0` (spec `3.7.0`), minio `RELEASE.2024-08-17` (spec `2024-06-13`), otel `0.104.0` (spec `0.102.0`), prometheus `v2.54.1` (spec `v2.53.0`), grafana `11.1.4` (spec `11.1.0`). No functional impact; docs-first means either match §2 or update §2 in the same change. *Fix or DEBT.*
- **[MINOR-2] Missing `postgres/00-extensions.sql` (`CREATE EXTENSION vector`).** [DockerCompose.md §1](../../docs/infrastructure/DockerCompose.md) layout and the §3 normative excerpt mount `./postgres/00-extensions.sql` into `docker-entrypoint-initdb.d`. The impl omits it — the `pgvector/pgvector:pg16` image ships the extension binary but does not enable it, so `vector` isn't `CREATE`d. Harmless now (no schema), but diverges from the spec and could surprise the first vector migration. *Fix (add the init SQL) or document that extension enablement is deferred to Flyway/eip-migrate.*
- **[MINOR-3] `.env.example` uses working dev values instead of the §1.1 `CHANGE_ME` convention.** [DockerCompose.md §1.1](../../docs/infrastructure/DockerCompose.md) states secret-bearing vars ship as `CHANGE_ME` and the app refuses to start on `CHANGE_ME`. The impl ships `eip_dev_pw`/`admin_dev_pw`/etc. Defensible for a dev-infra-only stack (boots out of the box; `.env` git-ignored; non-production), but it should converge with §1.1 once the app container is added so prod operators are forced to set real secrets. *Track for the app-container task.*

### NIT

- **[NIT-1] Compose file is `docker-compose.yml`; [DockerCompose.md §1](../../docs/infrastructure/DockerCompose.md) names it `compose.yaml`.** The TASK-0003 spec (TaskSpecs) said `docker-compose.yml`, so the author followed the task spec; the infra source-of-record uses `compose.yaml`. Align eventually (and reconcile the TaskSpecs vs DockerCompose.md naming).
- **[NIT-2] Volume names** `postgres-data`/`redis-data`/… vs §2's `eip_pg_data`/`eip_redis_data`/… Cosmetic (project-prefixed either way).
- **[NIT-3] Keycloak** container HTTP port set to `8080` (host `8180`); §2 implies KC internal `8180`. Host-facing `8180` matches; internal port differs. Harmless.
- **[NIT-4] Commit subject** lacks the `[TASK-0003]` trailer + `type(scope)` scope (user-mandated pattern; ID in subject). Squash-merge message should carry the full form.

### Appropriate scoping divergences (not findings — correct for this phase)

The impl `core` profile is **infra-only** (no `eip-app`/`eip-workers`/`frontend`/`eip-migrate` containers) — correct, those images don't exist yet and run on the host per the task scope; no Kafka topic creation (that's `eip-migrate`, later); host-published infra ports (dev backend runs on the host and must reach them — §2's "internal only" is the app-in-container production model); reduced smoke (the §13 checklist's app-dependent items can't run until the app exists — the applicable items, containers-healthy + buckets, pass).

## Evidence checked

| Area | How verified | Result |
|---|---|---|
| Diff scope | `git diff --name-status` base..branch | 10 files, all in the declared write-set; `.env` not in diff (git-ignored); no product-spec change |
| Compose file | Read committed `docker-compose.yml` + Makefile diff + configs | 9 services, profiles, healthchecks, dependency ordering; two healthcheck fixes present (keycloak realm-discovery probe, otel no-probe) |
| Conformance | Cross-read [DockerCompose.md §1–4/§10/§13](../../docs/infrastructure/DockerCompose.md) | Findings MINOR-1/2/3 + NIT-1/2/3; scoping divergences appropriate |
| Secrets | `git check-ignore .env`; grep diff | `.env` git-ignored; only `.env.example` (documented dev placeholders) tracked; no secrets committed |
| Ports (this host) | `lsof` | Only 5432 + 3001 occupied → limitation 1 confirmed as a host artifact, not a defect |

## Acceptance criteria status (independently re-run)

| AC | Reviewer verification | Status |
|---|---|---|
| AC-1 `make dev-up` healthy in ≤ 15 min | Stack reached healthy in **< 2 min**; **but the `make dev-up` target exits non-zero on fresh boot (MAJOR-1)** | ⚠️ stack OK, target fails |
| AC-2 healthchecks pass; ports/buckets match | 7/7 probe-capable services **healthy**; otel running (distroless, documented); buckets `eip-artifacts`+`eip-ingest` created; ports/buckets match reference | ✅ PASS |
| AC-3 `make dev-down` stops and cleans | `make dev-down` → volumes+network removed, containers gone, **exit 0** | ✅ PASS |
| AC-4 smoke (services reachable, buckets, realm) | Keycloak realm `eip` OIDC discovery → **200**, issuer correct; Prometheus scraping otel `:13133`; `make dev-urls` prints usable URLs | ✅ PASS |

## Validation command status (reviewer-run)

| Command | Result |
|---|---|
| `docker compose config -q` | VALID |
| `make dev-up` (fresh) | **Error 1 at 28 s** — stack healthy, exit code wrong (MAJOR-1) |
| `docker compose up -d --wait` (re-run, stack up) | exit 0 (confirms the one-shot race) |
| service health | postgres/redis/kafka/minio/prometheus/grafana/keycloak healthy; otel running |
| buckets | `eip-artifacts`, `eip-ingest` created |
| Keycloak realm/OIDC | discovery 200, issuer `…/auth/realms/eip` |
| `make dev-urls` | prints all endpoints + dev creds |
| `make dev-down` | exit 0, clean |
| `.env` git-ignored / secrets | ✓ ignored; none committed |

## CC-7, write-set, healthcheck design, MinIO init, provisioning stubs

- **CC-7 correct.** Infra tooling; no contract anchor, schema, or application code. No real secrets (dev placeholders in a git-ignored `.env`).
- **Write-set: clean.** All 10 files within the declared set; `Makefile` extension is the declared shared-file sequence from TASK-0001; no out-of-scope or product-spec changes.
- **Healthcheck design (keycloak, otel):** both handled correctly for their minimal images — otel is distroless (no shell → no container probe; serves `:13133`, Prometheus-scraped), keycloak 24 `start-dev` doesn't expose the 9000 mgmt port and ships no curl/wget → bash `/dev/tcp` probe against the realm OIDC discovery endpoint. Sound engineering; verified working.
- **MinIO bucket init:** the one-shot `minio-init` correctly gates on `minio: service_healthy`, creates both canonical buckets idempotently (`mb --ignore-existing`), exits 0. (Its interaction with `up --wait` is the root of MAJOR-1 — the init itself is correct; the *`make dev-up` wait strategy* is the defect.)
- **Prometheus/Grafana provisioning stubs:** Prometheus scrapes self + otel (app job commented for P0-E4-S3); Grafana provisions the Prometheus datasource + a dashboard provider pointing at an empty `json/` dir. Appropriate stubs; the app dashboards land in SPRINT-01.

## Known limitations assessment (the five the author documented)

| # | Limitation | Assessment | Rework? |
|---|---|---|---|
| 1 | Local NFR-050 timing used alternate ports (host has 5432/3001 occupied) | **Correct** — reviewer confirmed only 5432/3001 occupied; the `${VAR:-default}` overrides make it a config, not code, concern; defaults right for the clean 16 GB host | No |
| 2 | otel-collector no container healthcheck (distroless) | **Correct engineering**, documented | No |
| 3 | Full Keycloak/OIDC config → SPRINT-02 (P0-E3-S3) | **Correct scoping**; the realm stub works (OIDC discovery 200) | No |
| 4 | Built without reading DockerCompose.md (strict context) | This is where the conformance gaps live — the reviewer's cross-check surfaced MINOR-1/2/3 + NIT-1. **Reconcile the MINORs**, but no blocking rework | Fix/DEBT the MINORs |
| 5 | Grafana dashboards + app scrape targets are stubs | **Correct scoping** (P0-E4-S3) | No |

**None of the five documented limitations requires rework.** The blocking item is **MAJOR-1**, which the author did not flag (it surfaced under the reviewer's fresh `make dev-up`).

## Required fixes

- **MAJOR-1 (before merge, or waive with justification + DEBT):** make `make dev-up` exit 0 on a fresh boot by keeping the one-shot `minio-init` out of the `--wait` set.
- **MINOR-1/2/3 (fix now or DEBT):** reconcile image tags with DockerCompose.md §2; add `postgres/00-extensions.sql` (or document the Flyway deferral); converge `.env.example` on the §1.1 `CHANGE_ME` convention when the app container arrives.
- **NIT-1..4:** author's discretion; honor NIT-4 in the squash-merge message.

## Merge decision

**Not approved for merge.** One unwaived MAJOR (MAJOR-1) is open. The author fixes it (or R-DOA records a waiver + DEBT with the "re-run / ignore first-run exit code" rationale), then a re-review confirms a fresh `make dev-up` exits 0, after which this is APPROVED and may merge into `integration/SPRINT-00`. The stack functionality itself is sound; this is specifically about the one-command-boot contract.

## Exact next recommended command

```
Fix TASK-0003 MAJOR-1: change the `make dev-up` target so `docker compose up -d --wait` does not monitor the one-shot minio-init — e.g. `up -d --wait postgres redis kafka minio keycloak otel-collector prometheus grafana` then `docker compose ... up minio-init` (or `run --rm minio-init`) — and re-run `make dev-up` from a clean state to confirm it exits 0 with all services healthy and both buckets created. Reconcile MINOR-1 (align image tags to DockerCompose.md §2) and MINOR-2 (add infra/docker-compose/postgres/00-extensions.sql per §1/§3) now or file DEBT-005/DEBT-006; note MINOR-3 for the app-container task. Commit locally as "chore: fix TASK-0003 review findings", then request R-CR re-review of feature/TASK-0003-compose-dev-stack. Do not merge until the MAJOR is cleared. Do not start TASK-0004. Do not push.
```

---

*Review artifact per [AgentCommunicationProtocol](../../engineering-operating-system/AgentCommunicationProtocol.md). R-CR did not edit source (CodeReviewChecklist §5); the dev stack was torn down and the local `.env` removed, working tree clean. This file is uncommitted — the review checklists do not explicitly require committing the review document.*
