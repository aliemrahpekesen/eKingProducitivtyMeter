# Dependency Management

This document governs every third-party dependency in the EIP monorepo: how versions are declared (Gradle version catalog, pnpm), who approves new dependencies, which licenses are allowed, what air-gap fitness means, update cadence, lockfile discipline, the banned-dependency mechanism, and supply-chain controls. Engineering agents read it before adding, upgrading, or removing any dependency; R-DOA operates the scanning pipeline it mandates. EIP is an on-premise, air-gap-capable product (NFR-051, [../docs/product/PRD.md](../docs/product/PRD.md)) — every rule below exists to keep it shippable into a disconnected enterprise network.

## 1. Sources of truth

| Ecosystem | Version source | Integrity source | Rule |
|---|---|---|---|
| Backend (Gradle) | `/backend/gradle/libs.versions.toml` — the version catalog pins **all** dependency versions ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1) | `/backend/gradle/verification-metadata.xml` (SHA-256 checksums, §8) | Modules reference catalog aliases only; a literal version string in a `build.gradle.kts` fails G1 (enforced by the `eip.dependency-rules` convention plugin in `buildSrc`) |
| Frontend (pnpm) | `/frontend/package.json` (pnpm ≥ 9 pinned via the `packageManager` field, [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) §1) | `/frontend/pnpm-lock.yaml` integrity hashes | CI installs with `--frozen-lockfile`; npm/yarn are never used |
| Container images | Compose/K8s manifests under `/infra` pin exact image tags (never `latest`) per [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) §3.1 | Trivy scan at build (§10) | Image bumps are `build(infra):` PRs owned by R-DOA |

Dynamic versions are banned everywhere: no `+`, no `latest`, no version ranges in the catalog, no `^`/`~` semver ranges for **runtime** frontend dependencies (exact versions in `package.json`; dev-tooling MAY use `^` because the lockfile pins resolution).

## 2. Dependency classes

| Class | Definition | Approval required |
|---|---|---|
| Runtime | Ships in the delivered artifact (boot jars, container images, frontend bundle) | Owning architect of the consuming module (§3) + license allow-list (§4) + air-gap fitness (§5) |
| Test/build-only | Test frameworks, build plugins, codegen, linters — never in shipped artifacts | Owning architect; license check still runs, GPL-family tolerated only with R-CA + R-SA sign-off and proof it cannot leak into distributions |
| Transitive | Pulled in by a direct dependency | Scanned like direct deps; a problematic transitive is resolved by exclusion, catalog-pinned override, or replacing the direct dependency |

## 3. Approval flow for new runtime dependencies

Adding a runtime dependency is never a silent side effect of a feature PR. The sequence:

- [ ] 1. **Need check.** Confirm no approved dependency already covers the capability (the catalog is the inventory). Duplicating an existing capability (second HTTP client, second JSON mapper, second chart library) is rejected by default.
- [ ] 2. **Fitness check** against §4 (license) and §5 (air-gap). Record findings in the PR description.
- [ ] 3. **Owning-architect approval** via CODEOWNERS on the catalog/package.json diff: R-BA for backend shared scaffolding, the module owner per [./ModuleOwnership.md](./ModuleOwnership.md) §1 for module-scoped deps, R-FA for frontend, R-DOA for images. Dependencies entering `eip-core` (the shared kernel/SPI surface) additionally require **R-CA**.
- [ ] 4. **Framework-level additions require an ADR.** Anything that shapes architecture — a persistence framework, messaging client, DI extension, state-management library, agent framework — is decision-level: `ADR-NNN` per [./ADRProcess.md](./ADRProcess.md) before the code PR. Precedents: JdbcClient over jOOQ, outbox poller over Debezium, Quartz over ShedLock ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §5/§6/§8); Mantine over AntD, no Redux/Zustand without an ADR ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §1.1/§7).
- [ ] 5. The catalog/lockfile change, the code using it, and the SBOM impact land in **one PR** classed at least CC-7 (CC-2 if the dependency touches authn/z, crypto, secrets, or parsing of untrusted input).

### 3.1 Pre-approved stack

The following are approved by the specification itself and need no new approval — only version changes per §7. This list is the baseline inventory for the §3 need check.

| Area | Approved (source) |
|---|---|
| Backend core | Spring Boot 3.x, Spring Modulith, Spring Data JPA/Hibernate, Flyway, HikariCP ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1/§5/§12) |
| Backend infra clients | Kafka clients (KRaft mode), Redisson, MinIO/S3 SDK, Resilience4j, Quartz ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §6–§9) |
| Backend AI | LangChain4j ([../docs/product/PRD.md](../docs/product/PRD.md) §7 constraint 4) |
| Backend API/validation | springdoc, Jakarta Bean Validation, networknt json-schema-validator (draft 2020-12) ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §11) |
| Backend quality/build | Spotless + Google Java Format, Error Prone + NullAway, JSpecify, Checkstyle, ArchUnit, JaCoCo, logstash Logback encoder ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §4/§12, [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) §13) |
| Backend test | JUnit 5, AssertJ, Mockito, Testcontainers, WireMock, REST Assured ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §14) |
| Frontend runtime | React 18, TanStack Query/Router/Table, ECharts, Mantine, react-i18next, zod ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §1/§6) |
| Frontend build/test | Vite, TypeScript (strict), openapi-typescript, Vitest, Testing Library, MSW, axe, Playwright, ESLint + Prettier ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §1/§10) |
| Observability | OpenTelemetry SDK + Micrometer ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §12) |

## 4. License policy

| Status | Licenses | Rule |
|---|---|---|
| **Allowed (runtime)** | Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, EPL-1.0, EPL-2.0 | No further review beyond §3 |
| **Prohibited (runtime)** | GPL-family: GPL-2.0/3.0, AGPL-3.0, LGPL-2.1/3.0; SSPL, BUSL, Commons Clause, any "source-available" commercial license | MUST NOT ship in any delivered artifact — no exceptions, this is an on-premise product delivered into customer networks |
| **Case-by-case (build/test only)** | Anything else (MPL-2.0, CDDL, Unicode, zlib, …) | R-CA approval recorded in the PR; automated check keeps it out of runtime configurations |

The license check runs in the CI static stage on every PR ([../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §14 stage table). A dependency with no declared license is treated as prohibited. Dual-licensed artifacts are evaluated under the license we elect, stated in the PR.

## 5. Air-gap fitness criteria (NFR-051)

A dependency is air-gap fit only if **all** hold:

- [ ] **Mirrorable:** resolvable from Maven Central / npmjs.org (or another mirrorable public registry) so it can be replicated into the customer-side mirror; no vendor-portal-only downloads, no login-gated artifacts.
- [ ] **No phone-home:** no telemetry, usage analytics, update checks, or license activation at runtime (NFR-051: "no telemetry or license phone-home"). Libraries with opt-out telemetry MUST have it disabled by configuration committed with the dependency, verified by a test or documented flag.
- [ ] **No runtime downloads:** no fetching of models, drivers, rulesets, or data files at startup or first use. Anything the library needs at runtime ships inside our artifacts or the documented air-gap bundle (model weights are delivered via the mirror bundle, never fetched — [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §13).
- [ ] **Offline buildable:** the full build succeeds against the internal mirror with outbound network disabled. Release builds (CI package stage) run in this mode as a standing air-gap rehearsal.

## 6. Lockfile discipline

1. `pnpm-lock.yaml` and `verification-metadata.xml` are **committed always** ([./RepositoryRules.md](./RepositoryRules.md) §4) and regenerated only by the PR that changes dependencies.
2. CI uses `pnpm install --frozen-lockfile` and Gradle strict dependency verification; a lockfile/metadata drift fails G1.
3. Lockfile regeneration is its own commit within the PR (`build(frontend): refresh lockfile` / `build(eip-app): refresh verification metadata`) so reviewers can diff intent from mechanics.
4. Lockfile/metadata lines are excluded from the ~400-line PR size budget ([./BranchingStrategy.md](./BranchingStrategy.md) §4) but the **list of changed coordinates** MUST be summarized in the PR description.
5. Merge conflicts in lockfiles are resolved by regeneration, never by hand-editing.

## 7. Update cadence

| Trigger | Action | Deadline | Owner |
|---|---|---|---|
| Critical CVE in a shipped dependency | Patch PR (or exclusion/override) classed CC-2 | Immediate — within **2 working days**; criticals block release ([../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) security checklist) | R-DOA + owning architect |
| High CVE | Patch PR or documented waiver in the PR + DEBT entry | Next weekly batch; waiver expires in one phase | R-DOA + R-SA (waiver sign-off) |
| Routine minor/patch updates | **Weekly batch PR** per ecosystem: `build(eip-app): weekly dependency batch [TASK-NNNN]` / `build(frontend): …` | Weekly | R-DOA |
| Major version upgrades (Spring Boot, React, Kafka client, …) | Dedicated task with owning-architect review; ADR if behavior/architecture shifts | Scheduled by R-TPM, never inside a feature PR | Owning architect |
| Release branches (`release/v0.N`) | Security patches only — no routine or major upgrades | Per hotfix flow [./BranchingStrategy.md](./BranchingStrategy.md) §6 | R-RM |

Weekly batch PRs run the full gate set; a batch that breaks G2 is split until the offending upgrade is isolated, which then becomes its own task.

## 8. Banned-dependency list

The ban list is normative here and enforced mechanically: the `eip.dependency-rules` convention plugin (buildSrc) fails the backend build on banned coordinates in any resolvable configuration; a lint script in `/frontend` fails on banned packages in `package.json` or the lockfile. Changing this table requires R-CA approval, and the enforcement change ships in the same PR as the table edit.

| Banned | Scope | Reason (source of the decision) |
|---|---|---|
| Lombok | backend | Codebase is Lombok-free by convention ([../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) §12); records + explicit code instead |
| jOOQ | backend | Decided against: JdbcClient owns the analytics read side ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §5) |
| Debezium / Kafka Connect | backend/infra | Decided against: transactional outbox + in-process poller ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §6) |
| ShedLock | backend | Decided against: Quartz clustered JDBC store ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §8) |
| Spring WebFlux / reactive stack | backend | No reactive stack; blocking clients + virtual threads ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §4) |
| ZooKeeper (and ZK-mode Kafka clients/config) | infra | Kafka runs KRaft ([../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) §3.1) |
| Redux, Zustand, MobX | frontend | Server state lives in TanStack Query only; client state minimal — lift only via ADR ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §7) |
| Ant Design | frontend | Component library decision is Mantine ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §1.1) |
| styled-components, Emotion (runtime CSS-in-JS) | frontend | Tokens + CSS Modules; no runtime CSS-in-JS ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §1) |
| axios (and other HTTP clients) | frontend | Single generated OpenAPI client + typed fetch wrapper ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §1) |
| moment.js | frontend | Locale-aware dates via `Intl` ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §8) |
| Any GPL-family artifact | runtime everywhere | §4 |

MapStruct is additionally prohibited in production mapping code — DTO ↔ domain mapping is explicit static factory methods ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §2 item 4).

## 9. Removing and replacing dependencies

1. A dependency with zero remaining usages MUST be removed from the catalog/`package.json` in the PR that removes the last usage — dead entries rot the inventory and inflate scan surface.
2. Replacing a dependency (e.g., swapping a JSON-schema validator) is a dedicated task, never a side effect: old and new MUST NOT coexist across releases except during a declared migration window recorded as a DEBT entry with a target phase.
3. When a banned-list candidate is discovered already in the tree (usually transitive), the owning architect files a task to exclude or replace it; until resolved it is a RISK entry with R-SA as reviewer if security-relevant.

## 10. Supply-chain controls

| Control | Mechanism | Gate |
|---|---|---|
| Checksum verification | Gradle dependency verification (`verification-metadata.xml`, SHA-256) + pnpm lockfile integrity hashes | G1 — resolution fails on mismatch |
| Dependency vulnerability scan | OWASP Dependency-Check (`gradle dependencyCheckAnalyze`) + `pnpm audit` on every PR ([../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) security checklist) | G3 |
| Dependency review | CI dependency-review job diffs added/changed coordinates per PR against §4 licenses and §8 bans, and flags new transitive trees for the approving architect | G3 |
| Image scan | Trivy on all built container images; criticals block release, highs need documented waiver | G3 / RG2 |
| Secret scan | gitleaks on every PR (source + fixtures + simulation packs) | G3 |
| SBOM | CycloneDX SBOM generated at the package stage for `eip-app` + `eip-workers` images ([../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §16 stage 7); shipped with every release | RG2 |
| Single-mirror resolution | CI resolves exclusively through the internal mirror; release builds run with outbound network disabled (§5) | Package stage |
| SPI consumer pinning | Published SPIs (Connector, VectorStore, LLM provider, KMS) are semver-versioned per NFR-060 ([../docs/product/PRD.md](../docs/product/PRD.md)); implementations pin exact SPI versions ([./VersioningStrategy.md](./VersioningStrategy.md)) | G1 |

## Related documents

- [./RepositoryRules.md](./RepositoryRules.md) — committed lockfiles and generated-file rules
- [./BranchingStrategy.md](./BranchingStrategy.md) — where batch and hotfix dependency PRs flow
- [./ModuleOwnership.md](./ModuleOwnership.md) — which architect approves which module's dependencies
- [./ADRProcess.md](./ADRProcess.md) — framework-level dependency decisions
- [./SecurityChecklist.md](./SecurityChecklist.md) · [./QualityGatePolicy.md](./QualityGatePolicy.md) — G3/RG2 detail
- [./VersioningStrategy.md](./VersioningStrategy.md) — SPI semver (NFR-060) and platform versions
- [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1/§16 — version catalog and CI stages
- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) — the scanning stack behind G3
