# Versioning Strategy

This document is the single normative statement of every version scheme in the EIP program: platform releases, the four published SPIs, the REST API, event schemas, database migrations, frontend/backend coupling, and container image tags. It is read by every engineering agent before making any change that touches a versioned surface, by R-RM when cutting releases, and by R-CR when verifying that a PR's declared change class matches its version impact. Two agents applying this document independently MUST produce identical version decisions.

## 1. The versioning matrix

| Surface | Scheme | Compatibility rule | Bump authority | Source of record |
|---|---|---|---|---|
| Platform | `vX.Y.Z`, one minor per phase | §2 | R-RM (tags), phases fixed by [Roadmap §10](../docs/product/Roadmap.md) | Roadmap §1, §10 |
| Connector / VectorStore / LLM provider / KMS SPIs | `MAJOR.MINOR.PATCH`, independent per SPI | §3 | Owning architect + R-CA (MAJOR is CC-1) | NFR-060 ([PRD §6](../docs/product/PRD.md)), [ConnectorFramework §2.1](../docs/engineering/ConnectorFramework.md) |
| REST API | Path major `/api/v1` | §4 — additive-only within v1 | R-CA (breaking = CC-1, ADR required) | [APIDesign §3](../docs/engineering/APIDesign.md), FR-125 |
| Event payload schemas | `schemaVersion` `MAJOR.MINOR` per eventType | §5 | MINOR: owning architect (CC-4); MAJOR: R-CA (CC-1) | [EventModel §5–§6](../docs/engineering/EventModel.md), FR-038 |
| DB migrations | Flyway `V<seq>__<snake_case_summary>.sql` | §6 — forward-only, expand–contract | R-DBA (CC-4, gate G4) | FR-131, [DatabasePlan §7](../docs/engineering/DatabasePlan.md), [OperationsGuide §7](../docs/operations/OperationsGuide.md) |
| Frontend | Platform version (no independent scheme) | §7 — same train always | R-RM | Roadmap §10 |
| Container images | Immutable tags derived from platform version + SHA | §8 | R-DOA builds, R-RM releases | [DockerCompose §11](../docs/infrastructure/DockerCompose.md) |

## 2. Platform versioning

- Phase → version mapping is fixed: Phase 0 → v0.1, Phase 1 → v0.2, Phase 2 → v0.3, Phase 3 → v0.4, Phase 4 → v0.5, Phase 5 exit → v1.0 ([Roadmap §1, §10](../docs/product/Roadmap.md)). This mapping MUST NOT be renumbered; a slipped phase slips its version with it.
- Patch releases are `v0.N.P`, cut from `release/v0.N` per [ReleaseManagement §6](./ReleaseManagement.md); patches contain fixes only, never features.
- Git tags are `vX.Y.Z` ([BranchingStrategy §6](./BranchingStrategy.md)), created only by R-RM on a release branch head, and immutable: a tag is never moved or deleted; a bad release is superseded by the next patch.
- Pre-1.0 semantics: minor versions MAY change internal (non-anchor) behavior freely; contract anchors still follow their own rules in §3–§6 from the moment they ship. Post-1.0: full semantic versioning with deprecation windows on `/api/v1` (Roadmap §10).
- The running version is exposed at `GET /api/v1/system/info` ([APIDesign §4.12](../docs/engineering/APIDesign.md)); the upgrade verification checklist keys on it (OperationsGuide §7 step 5).

## 3. SPI versioning (Connector, VectorStore, LLM provider, KMS)

Per NFR-060 the four SPIs are published and semver-versioned **independently of the platform version and of each other**. The rules of [ConnectorFramework §2.1](../docs/engineering/ConnectorFramework.md) apply verbatim to all four SPIs, not only the Connector SPI:

- Each SPI carries `MAJOR.MINOR.PATCH`. MINOR/PATCH changes MUST be strictly additive and backward-compatible: new optional SPI methods with default implementations, new optional descriptor fields. Renames, removals, signature changes, and semantic changes of existing members bump MAJOR.
- **Deprecation window:** every breaking change MUST be preceded by at least one MINOR release in which the old and new forms coexist and use of the deprecated form is logged at registration (ConnectorFramework §2.1). Shipping a MAJOR without the preceding deprecation MINOR is a G4 BLOCKER.
- **Registration compatibility:** the platform accepts an implementation whose declared SPI MAJOR equals the platform's SPI MAJOR at any MINOR ≤ the platform's; an incompatible MAJOR is refused at registration with a classified `config` error (ConnectorFramework §2.1). This rule is contract-tested per SPI.
- Any SPI MAJOR bump is a CC-1 change: G4 with owning architect (R-CNA for Connector, R-AIA for VectorStore/LLM provider, R-SA co-review for KMS) + R-CA, ADR required, two-reviewer G8 ([QualityGatePolicy §3](./QualityGatePolicy.md)).
- SPI versions appear in release notes ([ReleaseManagement §5.1](./ReleaseManagement.md)) whenever they change; unchanged SPIs are not re-versioned just because the platform released.

## 4. REST API — `/api/v1`

- The API is path-versioned by major only. Within v1, changes MUST be additive: new endpoints, new optional request fields, new response fields, new enum values on fields documented as open-enum. Clients are required to tolerate unknown response fields ([APIDesign §3](../docs/engineering/APIDesign.md)).
- **Breaking** (removal/rename of fields or endpoints, type changes, semantics changes, tightened validation, closed-enum additions) requires `/api/v2` side-by-side — and is avoided pre-1.0 by design. Introducing `/api/v2` is CC-1: ADR + G4 (R-CA + R-BA) + two-reviewer G8.
- **Deprecation headers** (APIDesign §3, applied verbatim): once v2 exists, v1 responses carry `Deprecation: true`, `Sunset: <RFC 3339 date>`, and `Link: <migration doc>; rel="deprecation"`. Minimum deprecation window: **2 minor product releases or 6 months, whichever is longer** (on-premise upgrade cadence). Individual endpoints deprecated within v1 (rare, additive replacement must exist) use the same headers.
- G1 runs an OpenAPI diff on every PR ([QualityGatePolicy §2](./QualityGatePolicy.md)); any non-additive diff without a declared CC-1 class is a BLOCKER at G8 (misclassification rule, [QualityGatePolicy §3](./QualityGatePolicy.md)).

## 5. Event schema versioning

`schemaVersion` is `MAJOR.MINOR` per `(eventType)`, governed by [EventModel §5](../docs/engineering/EventModel.md) — restated here only as operating rules, EventModel remains the contract anchor:

- Within a MAJOR: **additive-only** — new optional fields, new open-enum values; never renames, removals, type or semantic changes. CI enforces this by diffing each schema against the prior minor and failing on non-additive change (EventModel §6, §15).
- Consumers register upcasters per `(eventType, fromMinor)`; handlers only ever see the newest MINOR of a supported MAJOR. A MINOR bump PR MUST land the upcaster and its round-trip test in the same PR (CC-4).
- **Unknown or newer MAJOR on consume → route to the group's `<group>.dlq` and alert immediately** (EventModel §5, FR-038). No consumer may guess, skip, or best-effort-parse an unsupported MAJOR.
- MAJOR bumps are CC-1 (event envelope/topics are contract anchors, [QualityGatePolicy §2](./QualityGatePolicy.md) G4). Inside the deploy unit, upgrade consumers first, then producers; parallel dual-major production exists only for consumers outside our deploy unit (EventModel §5).
- Producers always emit the latest version; there is no version negotiation.
- Schema files live in the in-repo registry `/backend/eip-core/src/main/resources/event-schemas/<eventType>/<major>.<minor>.json` (EventModel §6); adding a version means adding a file — existing schema files are never edited.

## 6. Database migration versioning

- All schema change is Flyway, `V<seq>__<snake_case_summary>.sql` (FR-131; naming per [DatabasePlan §7](../docs/engineering/DatabasePlan.md), the source of record). `<seq>` is an unpadded sequence number (`V1`, `V2`, …), strictly monotonic, allocated at merge time: the PR that merges first keeps its number; a competing PR MUST renumber on rebase. Duplicate or out-of-order version numbers are a G1 failure.
- **An applied migration is never edited — ever.** Not for typos, not for comments. Corrections are new migrations. Checksum drift in `flyway_schema_history` is an S1-class incident, and hand-editing that table is prohibited (OperationsGuide §7 step 6).
- Migrations are forward-only from v0.1 (Roadmap §10); there are no `U` (undo) migrations. Reversal = restore from backup + connector re-sync ([ReleaseManagement §7](./ReleaseManagement.md)).
- Breaking shape changes use **expand–contract** across at least two trains: expand (add nullable column/new table, dual-write) in release N; contract (drop old shape) no earlier than release N+1, after the release notes of N flagged the pending contraction. This is what makes every release one-minor backward compatible and rolling upgrades possible (OperationsGuide §7 step 3).
- Every migration PR is CC-4: gate G4 with R-DBA verdict; RLS policy changes within a migration are additionally CC-2 (R-SA).

## 7. Frontend/backend coupling

The frontend has **no independent version**. Frontend assets are built, tagged, tested, and shipped with the platform version of their train; a frontend-only fix still ships as a platform patch `v0.N.P`. The frontend consumes only `/api/v1` (FR-073), so within a train it MUST tolerate the additive API evolution of §4 — but cross-version deployment (frontend of one train against the backend of another) is unsupported and MUST fail visibly: the frontend compares its build version against `GET /api/v1/system/info` at startup and banners a mismatch.

## 8. Container image tagging

- Images: `eip/eip-app`, `eip/eip-workers`, `eip/frontend` (plus infra images pinned by digest in `/infra`). Release tags: `eip/<image>:vX.Y.Z` — exactly the platform tag, applied by R-DOA at release build, never re-pointed.
- Every CI build also tags `eip/<image>:git-<shortsha>` for traceability; CI tags are never used in release artifacts or `/infra` manifests.
- `latest` is prohibited everywhere — Compose/K8s manifests pin `EIP_VERSION` explicitly (DockerCompose §1.1 `.env` contract) and air-gapped installs load images from the signed offline bundle ([DockerCompose §11](../docs/infrastructure/DockerCompose.md)), where determinism is the whole point.
- Third-party images are pinned to exact versions (e.g. the DockerCompose §2 service catalog); upgrading a pin follows [DependencyManagement.md](./DependencyManagement.md).
- The offline bundle name embeds the platform version: `eip-offline-<version>.tar.gz`; its `sha256sums.txt` is signed (DockerCompose §11).

## 9. Where each version is observable

Every version claim MUST be verifiable at runtime or in the repo — reviewers and operators check these, not commit messages:

| Surface | Observable at |
|---|---|
| Platform | `GET /api/v1/system/info` (version, build SHA — [APIDesign §4.12](../docs/engineering/APIDesign.md)); git tag; `EIP_VERSION` in the deployment `.env` |
| SPI (each of the four) | SPI descriptor declared by every implementation at registration (ConnectorFramework §2.1); deprecation-use warnings in registration logs |
| REST | OpenAPI document served by springdoc for `/api/v1`; G1's stored OpenAPI diff artifacts per PR |
| Event schemas | `schemaVersion` field on every envelope ([EventModel §2](../docs/engineering/EventModel.md)); the in-repo registry index files |
| DB migrations | `flyway_schema_history` table (read-only for humans and agents alike); Flyway history check in the upgrade verification (OperationsGuide §7 step 5) |
| Images | Tag + digest in the offline bundle manifest and `sha256sums.txt`; `git-<shortsha>` label for CI builds |

Worked examples of well-formed identifiers:

```
platform tag        v0.3.1
release branch      release/v0.3
image               eip/eip-app:v0.3.1        (CI build: eip/eip-app:git-a1b2c3d)
offline bundle      eip-offline-v0.3.1.tar.gz
Connector SPI       2.1.0                     (a form deprecated in 2.1.0 may be removed in 3.0.0 —
                                               never in the same release that deprecates it, §3)
event schema        workitem.transitioned @ schemaVersion 1.2 →
                    file event-schemas/workitem.transitioned/1.2.json
migration           V41__add_metric_series_version.sql
```

## 10. Version-bump decision table

| You are changing… | Version consequence | Class | Sign-off |
|---|---|---|---|
| Internal code, no contract surface | none | CC-7 | default gates |
| REST: new endpoint/optional field | none (additive within `/api/v1`) | CC-7 (CC-2/3 if applicable) | G1 OpenAPI diff must be additive |
| REST: anything non-additive | `/api/v2` + deprecation headers | CC-1 | R-CA + ADR |
| Event payload: new optional field | `schemaVersion` MINOR + upcaster | CC-4 | R-DBA/owning architect at G4 |
| Event payload: rename/remove/retype | `schemaVersion` MAJOR | CC-1 | R-CA + ADR |
| SPI: new default method | SPI MINOR | CC-1-adjacent (published SPI) | owning architect |
| SPI: breaking signature change | SPI MAJOR after deprecation MINOR | CC-1 | owning architect + R-CA + ADR |
| DB: any schema change | new `V<seq>__<snake_case_summary>.sql` migration | CC-4 | R-DBA |
| Prompts/model routing in eip-ai | no platform version impact; eval suite | CC-5 | R-AIA |

## Related documents

- [ReleaseManagement.md](./ReleaseManagement.md) — trains, tags, patches, rollback using these schemes
- [BranchingStrategy.md](./BranchingStrategy.md) — branch and tag mechanics
- [QualityGatePolicy.md](./QualityGatePolicy.md) — G1 OpenAPI/schema diffs, G4 anchor review
- [ADRProcess.md](./ADRProcess.md) — required for every MAJOR/CC-1 decision here
- [DependencyManagement.md](./DependencyManagement.md) — third-party version pins
- [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §5–§6 — event schema evolution (contract anchor)
- [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) §3 — REST versioning & deprecation (contract anchor)
- [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md) §2.1 — SPI semver and deprecation window
- [../docs/product/PRD.md](../docs/product/PRD.md) NFR-060, FR-125, FR-131, FR-038
- [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §10 — release train & versioning policy
